package app.olauncher.ui.search

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.text.Spannable
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ScrollView
import android.graphics.Color
import android.graphics.Rect
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.AppCommand
import app.olauncher.data.AppCommandQuery
import app.olauncher.data.AppCommandSession
import app.olauncher.data.AppSearchAppearance
import app.olauncher.data.Constants
import app.olauncher.data.Contact
import app.olauncher.data.ContactCommand
import app.olauncher.data.ContactCommandQuery
import app.olauncher.data.ContactCommandSession
import app.olauncher.data.ContactsLoader
import app.olauncher.data.GroupChat
import app.olauncher.data.GroupChatsLoader
import app.olauncher.data.Prefs
import app.olauncher.data.SearchEngine
import app.olauncher.data.SearchEngines
import app.olauncher.data.SearchMode
import app.olauncher.databinding.FragmentUniversalSearchBinding
import app.olauncher.databinding.PopupContactActionsBinding
import app.olauncher.databinding.PopupSearchPageMenuBinding
import app.olauncher.helper.AppSearchMatcher
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.createDialog
import app.olauncher.helper.OlDialog
import app.olauncher.helper.isSystemApp
import app.olauncher.ui.BaseFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** One search page shared by the A, C, E and S shortcuts; [SearchMode] decides what it shows. */
class UniversalSearchFragment : BaseFragment(), SearchActions {

    private lateinit var prefs: Prefs
    private lateinit var mode: SearchMode
    private lateinit var adapter: SearchResultsAdapter
    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentUniversalSearchBinding? = null
    private val binding get() = _binding!!

    private var apps: List<AppModel> = emptyList()
    private var privateApps: List<AppModel> = emptyList()
    private var privateSpaceLocked = true
    private var contacts: List<Contact> = emptyList()
    private var groupChats: List<GroupChat> = emptyList()
    private var messagesPermissionRequested = false
    private var contactsRefreshJob: Job? = null
    private var engines: List<SearchEngine> = emptyList()
    private var filterJob: Job? = null
    private var permissionRequested = false
    private var searchTextView: TextView? = null
    private var autoLaunchedQuery: String? = null
    private var searchTileCount = 1
    private var appAppearance: AppSearchAppearance? = null
    private var activePopupWindow: PopupWindow? = null
    private var commandSession = ContactCommandSession()
    private var commandJob: Job? = null
    private var commandExecutor: ContactCommandExecutor? = null
    private var commandSubmitQuery: String? = null
    private var filterGeneration = 0L
    private var restoringCommandState = false
    private var displayedCommand: ContactCommand? = null
    private var commandCodeIconSpan: ImageSpan? = null
    private var appCommandSession = AppCommandSession()
    private var appCommandJob: Job? = null
    private var appCommandChip: AppCommandChipSpan? = null
    private var displayedAppCommand: AppCommand? = null
    private val massUninstallSelection = linkedMapOf<String, AppModel.App>()
    private var pendingMassUninstallKeys: Set<String> = emptySet()
    private var massUninstallMode = false
    private var engineEditChip: EngineEditChipSpan? = null
    private var renameDialog: OlDialog? = null
    private var massUninstallDialog: androidx.appcompat.app.AlertDialog? = null
    private var appsReady = false
    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsRefreshJob?.cancel()
            contactsRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(200)
                viewModel.loadContacts()
            }
        }
    }
    private var messagesObserverRegistered = false
    private val messagesObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            messagesRefreshJob?.cancel()
            messagesRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(200)
                viewModel.loadGroupChats()
            }
        }
    }
    private var messagesRefreshJob: Job? = null

    private val popupBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            activePopupWindow?.dismiss()
        }
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.loadContacts()
        else context?.showToast(R.string.contacts_permission_denied)
        rebuild()
    }
    private val messagesPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            registerMessagesObserver()
            viewModel.loadGroupChats()
        } else context?.showToast(R.string.messages_permission_denied)
        rebuild()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUniversalSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        mode = SearchMode.from(arguments?.getString(Constants.Key.SEARCH_MODE))
        displayedCommand = null
        displayedAppCommand = null
        if (mode == SearchMode.APPS_ONLY) {
            restoringCommandState = savedInstanceState != null
            if (savedInstanceState != null) {
                appCommandSession = AppCommandSession(
                    savedInstanceState.getString("appCommandQuery").orEmpty(),
                    savedInstanceState.getBoolean("appCommandConsumed"),
                )
                pendingMassUninstallKeys = savedInstanceState.getStringArrayList("massUninstallSelection")?.toSet().orEmpty()
            }
        }
        if (mode == SearchMode.CONTACTS_ONLY) {
            restoringCommandState = savedInstanceState != null
            if (savedInstanceState != null) {
                commandSession = ContactCommandSession(
                    savedInstanceState.getString("contactCommandQuery").orEmpty(),
                    savedInstanceState.getBoolean("contactCommandConsumed"),
                )
            }
            commandExecutor = ContactCommandExecutor(requireContext(), viewLifecycleOwner)
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, popupBackCallback)

        initSearchView()
        initRecyclerView()
        initObservers()

        if (mode == SearchMode.CONTACTS_ONLY && savedInstanceState == null && !ContactsLoader.hasPermission(requireContext()))
            requestContactsPermission()
        rebuild()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        restoringCommandState = false
        if (mode == SearchMode.CONTACTS_ONLY || mode == SearchMode.APPS_ONLY) rebuild()
    }

    private fun initSearchView() {
        binding.modeTitle.apply {
            text = when (mode) {
                SearchMode.APPS_ONLY -> getString(R.string.apps)
                SearchMode.CONTACTS_ONLY -> getString(R.string.contacts)
                SearchMode.ENGINES_ONLY -> getString(R.string.search_heading_engines)
                SearchMode.ALL_SEARCH -> getString(R.string.search_anything)
            }
            gravity = prefs.appLabelAlignment
            visibility = View.VISIBLE
        }
        val icon = when (mode) {
            SearchMode.APPS_ONLY -> R.drawable.ic_search_apps
            SearchMode.CONTACTS_ONLY -> R.drawable.ic_person
            SearchMode.ENGINES_ONLY -> R.drawable.ic_search_web
            SearchMode.ALL_SEARCH -> R.drawable.ic_search
        }
        binding.modeIcon.setImageResource(icon)
        binding.emptyIcon.setImageResource(icon)
        binding.emptyMessage.setText(when (mode) {
            SearchMode.APPS_ONLY -> R.string.search_no_apps
            SearchMode.CONTACTS_ONLY -> R.string.search_no_contacts
            SearchMode.ENGINES_ONLY -> R.string.search_no_engines
            SearchMode.ALL_SEARCH -> R.string.search_no_results
        })
        binding.resultsCount.text = resources.getQuantityString(R.plurals.search_result_count, 0, 0)
        binding.search.queryHint = ""
        searchTextView = binding.search.findViewById(R.id.search_src_text)
        searchTextView?.apply {
            gravity = (prefs.appLabelAlignment and Gravity.VERTICAL_GRAVITY_MASK.inv()) or Gravity.CENTER_VERTICAL
            background = null
            backgroundTintList = null
            isCursorVisible = true
            textSize = 18f
            minimumWidth = 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                setTextCursorDrawable(R.drawable.search_cursor)
            setOnFocusChangeListener { _, focused -> binding.search.isActivated = focused }
        }
        binding.search.isActivated = searchTextView?.hasFocus() == true
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                submit(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                rebuild()
                return true
            }
        })
        binding.searchPageMenu.setOnClickListener { toggleSearchPageMenu() }
        binding.massUninstallAction.setOnClickListener { confirmMassUninstall() }
    }

    private fun initRecyclerView() {
        searchTileCount = prefs.searchTileCount(mode)
        adapter = SearchResultsAdapter(prefs.appLabelAlignment, this, searchTileCount,
            appCommandsEnabled = mode == SearchMode.APPS_ONLY,
            engineOverflowEnabled = mode != SearchMode.ENGINES_ONLY)
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), searchTileCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    when (adapter.currentList.getOrNull(position)) {
                        is SearchItem.Header, is SearchItem.Permission, is SearchItem.AddContact -> spanCount
                        else -> 1
                    }
            }
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        applyAppSearchAppearance()
        binding.recyclerView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            appAppearance?.applyResultsPadding(view, searchTileCount)
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) binding.search.hideKeyboard()
            }
        })

        val horizontalPadding = binding.searchRoot.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchRoot) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = horizontalPadding + safe.left, right = horizontalPadding + safe.right,
                top = safe.top, bottom = max(ime, safe.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.searchRoot)
    }

    private fun initObservers() {
        engines = SearchEngines.get(requireContext())
        if (mode.showsEngines)
            SearchEngines.engines.observe(viewLifecycleOwner) {
                engines = it
                rebuild()
            }
        if (mode.showsApps) {
            viewModel.appList.observe(viewLifecycleOwner) {
                appsReady = it != null
                apps = it.orEmpty().filter { app -> app !is AppModel.PrivateSpaceHeader && app.appPackage.isNotEmpty() }
                restoreMassUninstallSelection()
                rebuild()
            }
            viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                privateSpaceLocked = it
                rebuild()
            }
            viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                privateApps = it.orEmpty()
                restoreMassUninstallSelection()
                rebuild()
            }
        }
        if (mode.showsContacts) {
            viewModel.contacts.observe(viewLifecycleOwner) {
                contacts = it
                rebuild()
            }
            viewModel.contactsLoading.observe(viewLifecycleOwner) { rebuild() }
            viewModel.groupChats.observe(viewLifecycleOwner) {
                groupChats = it
                rebuild()
            }
            viewModel.groupChatsLoading.observe(viewLifecycleOwner) { rebuild() }
        }
    }

    private fun toggleSearchPageMenu() {
        activePopupWindow?.let {
            it.dismiss()
            return
        }
        appCommandJob?.cancel()
        val popupBinding = PopupSearchPageMenuBinding.inflate(layoutInflater)
        popupBinding.searchPageSettings.isVisible = mode == SearchMode.ENGINES_ONLY
        popupBinding.searchPageAppearance.isVisible = false
        popupBinding.contactCommands.isVisible = mode == SearchMode.CONTACTS_ONLY ||
            mode == SearchMode.APPS_ONLY || mode == SearchMode.ENGINES_ONLY
        if (mode == SearchMode.CONTACTS_ONLY || mode == SearchMode.APPS_ONLY || mode == SearchMode.ENGINES_ONLY) {
            val density = resources.displayMetrics.density
            val commands = if (mode == SearchMode.APPS_ONLY) listOf(
                Triple(AppCommand.RENAME.code, appCommandIcon(AppCommand.RENAME), R.string.rename),
                Triple(AppCommand.INFO.code, appCommandIcon(AppCommand.INFO), R.string.app_command_info),
                Triple(AppCommand.PLAY_STORE.code, appCommandIcon(AppCommand.PLAY_STORE), R.string.app_command_store),
                Triple(AppCommand.UNINSTALL.code, appCommandIcon(AppCommand.UNINSTALL), R.string.app_command_uninstall),
                Triple(AppCommand.MASS_UNINSTALL.code, appCommandIcon(AppCommand.MASS_UNINSTALL), R.string.app_command_mass_uninstall),
            ) else if (mode == SearchMode.ENGINES_ONLY) listOf(
                Triple("e", R.drawable.ic_rename, R.string.app_command_engine_edit),
            ) else listOf(
                Triple(ContactCommand.PHONE.letter.toString(), R.drawable.ic_call, R.string.contact_command_phone),
                Triple(ContactCommand.MESSAGE.letter.toString(), R.drawable.ic_message, R.string.contact_command_message),
                Triple(ContactCommand.WHATSAPP.letter.toString(), R.drawable.ic_whatsapp, R.string.whatsapp),
                Triple(ContactCommand.CONTACT.letter.toString(), R.drawable.ic_person, R.string.contact_command_page),
            )
            val commandGap = resources.getDimensionPixelSize(R.dimen.search_menu_item_gap)
            commands.forEachIndexed { index, (command, icon, label) ->
                val entry = TextView(requireContext()).apply {
                    text = getString(R.string.contact_command_menu_entry,
                        command.uppercase(), getString(label))
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                    compoundDrawablePadding = (12 * density).toInt()
                    val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
                        setBounds(0, 0, (20 * density).toInt(), (20 * density).toInt())
                        setTint(Color.WHITE)
                    }
                    setCompoundDrawablesRelative(drawable, null, null, null)
                    if (mode == SearchMode.CONTACTS_ONLY) {
                        setOnLongClickListener {
                            cancelAutomaticCommand()
                            activePopupWindow?.dismiss()
                            ContactCommand.entries.firstOrNull { it.letter.toString() == command }?.let {
                                commandExecutor?.openApp(it)
                            }
                            true
                        }
                    }
                    setOnClickListener {
                        val query = currentQuery()
                        val name = when (mode) {
                            SearchMode.APPS_ONLY -> AppCommandQuery.parse(query)?.name ?: query.trim()
                            SearchMode.ENGINES_ONLY -> SearchEngines.SearchEngineCommandQuery.parse(query)?.name ?: query.trim()
                            else -> ContactCommandQuery.parse(query)?.name ?: query.trim()
                        }
                        activePopupWindow?.dismiss()
                        binding.search.setQuery("$command $name", false)
                        when (mode) {
                            SearchMode.APPS_ONLY -> updateAppCommandChip()
                            SearchMode.ENGINES_ONLY -> updateEngineEditChip()
                            else -> updateCommandIndicator()
                        }
                        searchTextView?.requestFocus()
                        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(searchTextView, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                popupBinding.contactCommands.addView(entry,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                        topMargin = if (index == 0) 0 else commandGap
                    })
            }
        }
        updateTileCountControls(popupBinding)
        popupBinding.searchPageSettings.setOnClickListener {
            activePopupWindow?.dismiss()
            if (mode == SearchMode.APPS_ONLY) {
                findNavController().navigate(R.id.appSearchSettingsFragment)
            } else if (mode == SearchMode.ENGINES_ONLY) {
                findNavController().navigate(R.id.searchEnginesFragment)
            } else if (mode == SearchMode.CONTACTS_ONLY) {
                findNavController().navigate(R.id.contactSearchSettingsFragment)
            } else {
                findNavController().navigate(
                    R.id.settingsFragment,
                    Bundle().apply { putBoolean(Constants.Key.OPEN_ACES_SETTINGS, true) }
                )
            }
        }
        popupBinding.decreaseTileCount.setOnClickListener {
            if (searchTileCount > 1) updateSearchTileCount(searchTileCount - 1, popupBinding)
        }
        popupBinding.increaseTileCount.setOnClickListener {
            if (searchTileCount < 2) updateSearchTileCount(searchTileCount + 1, popupBinding)
        }
        val popup = createPopup(popupBinding.root, 256, R.drawable.bg_quick_panel)
        prefs.searchAppearance(mode, searchTileCount).applyMenu(popupBinding)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        showPopup(popup, binding.searchPageMenu, popupBinding.root)
    }

    private fun updateTileCountControls(binding: PopupSearchPageMenuBinding) {
        binding.tileCount.setText(
            if (searchTileCount == 1) R.string.one_tile_across else R.string.two_tiles_across
        )
        binding.decreaseTileCount.isEnabled = searchTileCount > 1
        binding.decreaseTileCount.alpha = if (searchTileCount > 1) 1f else 0.45f
        binding.increaseTileCount.isEnabled = searchTileCount < 2
        binding.increaseTileCount.alpha = if (searchTileCount < 2) 1f else 0.45f
    }

    private fun updateSearchTileCount(count: Int, menuBinding: PopupSearchPageMenuBinding) {
        val newCount = count.coerceIn(1, 2)
        if (newCount == searchTileCount) return
        searchTileCount = newCount
        prefs.setSearchTileCount(mode, newCount)

        val layoutManager = binding.recyclerView.layoutManager as GridLayoutManager
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        val firstVisibleView = layoutManager.findViewByPosition(firstVisiblePosition)
        val firstVisibleOffset = firstVisibleView?.top ?: 0
        layoutManager.spanCount = newCount
        adapter.setTileCount(newCount)
        applyAppSearchAppearance()
        prefs.searchAppearance(mode, newCount).applyMenu(menuBinding)
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        if (firstVisiblePosition != RecyclerView.NO_POSITION)
            layoutManager.scrollToPositionWithOffset(firstVisiblePosition, firstVisibleOffset)
        updateTileCountControls(menuBinding)
    }

    private fun applyAppSearchAppearance() {
        val appearance = prefs.searchAppearance(mode, searchTileCount)
        appAppearance = appearance
        binding.searchRoot.setBackgroundColor(
            appearance.background(ContextCompat.getColor(requireContext(), R.color.search_background))
        )
        val foreground = android.graphics.Color.WHITE
        binding.modeTitle.setTextColor(foreground)
        binding.searchPageMenu.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
        searchTextView?.setTextColor(foreground)
        searchTextView?.setHintTextColor(foreground)
        adapter.setAppAppearance(appearance)
        appearance.applyResultsPadding(binding.recyclerView, searchTileCount)
    }

    private fun showContactActions(contact: Contact, anchor: View) {
        activePopupWindow?.dismiss()
        val actions = PopupContactActionsBinding.inflate(layoutInflater)
        val popup = createPopup(actions.root, 160, R.drawable.bg_search_result, heightDp = 56)
        popup.contentView.isVerticalScrollBarEnabled = false
        popup.contentView.isHorizontalScrollBarEnabled = false
        // Let outside taps reach other contact tiles instead of only dismissing this popup.
        popup.isFocusable = false
        actions.contactCallAction.setOnClickListener {
            popup.dismiss()
            onContactCall(contact)
        }
        actions.contactTextAction.setOnClickListener {
            popup.dismiss()
            onContactText(contact)
        }
        actions.contactWhatsAppAction.setOnClickListener {
            popup.dismiss()
            onContactWhatsApp(contact)
        }
        actions.root.background = searchTileSurface(requireContext(), appAppearance?.popupColor)
        listOf(actions.contactCallAction, actions.contactTextAction, actions.contactWhatsAppAction).forEach {
            it.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            it.setBackgroundResource(R.drawable.bg_search_button)
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        showPopup(popup, anchor, actions.root, centerOnAnchor = true)
    }

    private fun createPopup(content: View, widthDp: Int, background: Int, heightDp: Int? = null): PopupWindow {
        val density = resources.displayMetrics.density
        val maxWidth = (resources.displayMetrics.widthPixels - 24 * density).toInt().coerceAtLeast(1)
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = false
            addView(content)
        }
        return PopupWindow(
            scroll,
            minOf((widthDp * density).toInt(), maxWidth),
            heightDp?.let { (it * density).toInt() } ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(requireContext(), background))
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            elevation = 0f
            setOnDismissListener {
                if (activePopupWindow === this) activePopupWindow = null
                popupBackCallback.isEnabled = activePopupWindow?.isShowing == true
            }
        }
    }

    private fun showPopup(
        popup: PopupWindow,
        anchor: View,
        content: View,
        centerOnAnchor: Boolean = false,
    ) {
        activePopupWindow?.dismiss()
        activePopupWindow = popup
        popupBackCallback.isEnabled = true
        val visible = Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(visible.height(), View.MeasureSpec.AT_MOST),
        )
        val popupHeight = if (popup.height > 0) popup.height else content.measuredHeight
        val bounds = if (centerOnAnchor) {
            centeredSearchPopupBounds(
                visible, location[0], location[1], anchor.width, anchor.height, popup.width, popupHeight,
            )
        } else {
            searchPopupBounds(visible, location[0], location[1], anchor.width, anchor.height, popup.width, popupHeight)
        }
        popup.width = bounds.width()
        popup.height = bounds.height()
        val windowOrigin = IntArray(2)
        val screenOrigin = IntArray(2)
        binding.root.getLocationInWindow(windowOrigin)
        binding.root.getLocationOnScreen(screenOrigin)
        popup.showAtLocation(binding.root, Gravity.TOP or Gravity.LEFT,
            bounds.left - screenOrigin[0] + windowOrigin[0],
            bounds.top - screenOrigin[1] + windowOrigin[1])
    }

    private fun currentQuery() = _binding?.search?.query?.toString().orEmpty()

    private fun rebuild() {
        if (_binding == null || !::adapter.isInitialized) return
        if (restoringCommandState) return
        val query = currentQuery()
        binding.emptyState.isVisible = false
        val generation = ++filterGeneration
        if (mode == SearchMode.APPS_ONLY) {
            appCommandJob?.cancel()
            appCommandSession.update(query)
            updateAppCommandChip()
            updateMassUninstallUi(AppCommandQuery.parse(query)?.command == AppCommand.MASS_UNINSTALL)
        } else if (mode == SearchMode.ENGINES_ONLY) {
            updateEngineEditChip()
        }
        if (mode == SearchMode.CONTACTS_ONLY) {
            commandJob?.cancel()
            if (commandSession.query != query) {
                commandExecutor?.cancel()
                commandSubmitQuery = null
            }
            commandSession.update(query)
            updateCommandIndicator()
        }
        val appsSnapshot = if (privateSpaceLocked) apps else apps + privateApps
        val contactsSnapshot = contacts
        val groupsSnapshot = groupChats
        val enginesSnapshot = engines
        val contactsGranted = ContactsLoader.hasPermission(requireContext())
        val messagesGranted = GroupChatsLoader.hasPermission(requireContext())
        val titles = Titles(
            apps = getString(R.string.apps),
            contacts = getString(R.string.contacts),
            search = getString(R.string.search),
            permission = getString(R.string.allow_contacts_access),
            messagesPermission = getString(R.string.allow_messages_access),
        )
        filterJob?.cancel()
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.Default) {
                buildItems(query, appsSnapshot, contactsSnapshot, enginesSnapshot, contactsGranted, titles,
                    groupsSnapshot, messagesGranted)
            }
            adapter.submitList(items) {
                if (_binding == null || query != currentQuery() || generation != filterGeneration) return@submitList
                val count = items.count { it is SearchItem.App || it is SearchItem.ContactItem ||
                    it is SearchItem.GroupChatItem || it is SearchItem.Engine }
                binding.resultsCount.text = resources.getQuantityString(R.plurals.search_result_count, count, count)
                val loading = (mode.showsContacts && viewModel.contactsLoading.value == true) ||
                    (mode.showsContacts && viewModel.groupChatsLoading.value == true) || (mode.showsApps && !appsReady)
                binding.emptyState.isVisible = count == 0 && !loading &&
                    items.none { it is SearchItem.Permission || it is SearchItem.AddContact }
                if (mode == SearchMode.CONTACTS_ONLY) {
                    if (commandSubmitQuery == query) {
                        commandSubmitQuery = null
                        if (viewModel.contactsLoading.value != true) {
                            items.filterIsInstance<SearchItem.ContactItem>().singleOrNull()?.let {
                                executeContactCommand(it.contact, query)
                            }
                        }
                    } else scheduleContactCommand(query, items)
                    return@submitList
                }
                if (mode == SearchMode.APPS_ONLY && AppCommandQuery.parse(query) != null) {
                    scheduleAppCommand(query, items)
                    return@submitList
                }
                if (mode != SearchMode.APPS_ONLY || query.isBlank() || query.startsWith(" ") ||
                    query != currentQuery() || isSearchComposing() || query == autoLaunchedQuery
                ) return@submitList

                val soleResult = items.filterIsInstance<SearchItem.App>().singleOrNull() ?: return@submitList
                autoLaunchedQuery = query
                onAppClick(soleResult.app)
            }
        }
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        return start >= 0 && end > start
    }

    private fun appCommandIcon(command: AppCommand): Int = when (command) {
        AppCommand.RENAME -> R.drawable.ic_rename
        AppCommand.INFO -> R.drawable.ic_info
        AppCommand.PLAY_STORE -> R.drawable.ic_play_store
        AppCommand.UNINSTALL -> R.drawable.ic_delete
        AppCommand.MASS_UNINSTALL -> R.drawable.ic_delete
    }

    private fun updateAppCommandChip() {
        val parsed = AppCommandQuery.parse(currentQuery())
        val editable = searchTextView?.text as? Spannable ?: return
        val spanEnd = parsed?.command?.code?.length ?: 1
        if (parsed?.command == displayedAppCommand && (parsed == null || appCommandChip?.let {
            editable.getSpanStart(it) == 0 && editable.getSpanEnd(it) == spanEnd
            } == true)
        ) return
        appCommandChip?.let(editable::removeSpan)
        appCommandChip = null
        displayedAppCommand = parsed?.command
        if (parsed != null) {
            AppCommandChipSpan(requireContext(), parsed.command, appCommandIcon(parsed.command)).also {
                editable.setSpan(it, 0, parsed.command.code.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                appCommandChip = it
            }
        }
    }

    private fun updateEngineEditChip() {
        val parsed = if (mode == SearchMode.ENGINES_ONLY) SearchEngines.SearchEngineCommandQuery.parse(currentQuery()) else null
        val editable = searchTextView?.text as? Spannable ?: return
        if (parsed != null && engineEditChip?.let {
                editable.getSpanStart(it) == 0 && editable.getSpanEnd(it) == 1
            } == true
        ) return
        engineEditChip?.let(editable::removeSpan)
        engineEditChip = null
        if (parsed != null) {
            EngineEditChipSpan(requireContext(), R.drawable.ic_rename).also { span ->
                editable.setSpan(span, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                engineEditChip = span
            }
        }
    }

    private fun scheduleAppCommand(query: String, items: List<SearchItem>) {
        appCommandJob?.cancel()
        val matches = items.filterIsInstance<SearchItem.App>()
        if (!appCommandSession.automaticCandidate(matches.size, appsReady,
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), isSearchComposing()) ||
            activePopupWindow?.isShowing == true
        ) return
        appCommandJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(250)
            if (_binding != null && currentQuery() == query && activePopupWindow?.isShowing != true &&
                appCommandSession.automaticCandidate(
                    adapter.currentList.filterIsInstance<SearchItem.App>().size, appsReady,
                    viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), isSearchComposing(),
                )
            ) executeAppCommand(matches.single().app, query)
        }
    }

    private fun executeAppCommand(app: AppModel, query: String = currentQuery()) {
        appCommandJob?.cancel()
        val parsed = AppCommandQuery.parse(query) ?: return
        if (query != currentQuery() || isSearchComposing() ||
            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !AppSearchMatcher(parsed.name).matches(app.appLabel) ||
            adapter.currentList.filterIsInstance<SearchItem.App>().none { it.app == app }
        ) return
        if (!appCommandSession.claim(query)) return
        activePopupWindow?.dismiss()
        try {
            when (parsed.command) {
                AppCommand.RENAME -> showAppRename(app)
                AppCommand.INFO -> openAppInfo(requireContext(), app.user, app.appPackage)
                AppCommand.PLAY_STORE -> {
                    val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.appPackage}"))
                        .setPackage("com.android.vending")
                    if (!startSafely(store) && !startSafely(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=${app.appPackage}"))))
                        requireContext().showToast(R.string.unable_to_open_app)
                }
                AppCommand.UNINSTALL -> when {
                    app !is AppModel.App -> requireContext().showToast(R.string.app_command_unsupported)
                    app.user != android.os.Process.myUserHandle() -> {
                        requireContext().showToast(R.string.app_command_profile_uninstall)
                        openAppInfo(requireContext(), app.user, app.appPackage)
                    }
                    requireContext().isSystemApp(app.appPackage, app.user) -> {
                        requireContext().showToast(R.string.system_app_cannot_delete)
                        openAppInfo(requireContext(), app.user, app.appPackage)
                    }
                    !startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.appPackage}"))) ->
                        requireContext().showToast(R.string.unable_to_open_app)
                }
                AppCommand.MASS_UNINSTALL -> updateMassUninstallUi(true)
            }
        } catch (_: SecurityException) {
            requireContext().showToast(R.string.unable_to_open_app)
        } catch (_: ActivityNotFoundException) {
            requireContext().showToast(R.string.unable_to_open_app)
        }
    }

    private fun appKey(app: AppModel.App): String = "${app.appPackage}|${app.user}"

    private fun restoreMassUninstallSelection() {
        if (pendingMassUninstallKeys.isEmpty()) return
        val available = (apps + privateApps).filterIsInstance<AppModel.App>().associateBy(::appKey)
        pendingMassUninstallKeys.forEach { key -> available[key]?.let { massUninstallSelection[key] = it } }
        pendingMassUninstallKeys = pendingMassUninstallKeys - massUninstallSelection.keys
        updateMassUninstallUi()
    }

    private fun updateMassUninstallUi(enabled: Boolean = massUninstallMode) {
        massUninstallMode = enabled && mode == SearchMode.APPS_ONLY
        if (!::adapter.isInitialized || _binding == null) return
        adapter.setMassUninstallState(massUninstallMode, massUninstallSelection.keys)
        binding.massUninstallAction.apply {
            isVisible = massUninstallMode && massUninstallSelection.isNotEmpty()
            text = getString(R.string.mass_uninstall_action, massUninstallSelection.size)
        }
        binding.resultsCount.isVisible = !massUninstallMode || massUninstallSelection.isEmpty()
    }

    override fun onMassUninstallToggle(app: AppModel.App) {
        if (app.user != android.os.Process.myUserHandle()) {
            requireContext().showToast(R.string.app_command_profile_uninstall)
            adapter.refreshMassUninstallSelection()
            openAppInfo(requireContext(), app.user, app.appPackage)
            return
        }
        if (requireContext().isSystemApp(app.appPackage, app.user)) {
            requireContext().showToast(R.string.system_app_cannot_delete)
            adapter.refreshMassUninstallSelection()
            openAppInfo(requireContext(), app.user, app.appPackage)
            return
        }
        val key = appKey(app)
        if (massUninstallSelection.remove(key) == null) massUninstallSelection[key] = app
        updateMassUninstallUi()
    }

    private fun confirmMassUninstall() {
        val selected = massUninstallSelection.values.toList().filter { app ->
            app.user == android.os.Process.myUserHandle() && !requireContext().isSystemApp(app.appPackage, app.user)
        }
        if (selected.isEmpty()) {
            requireContext().showToast(R.string.mass_uninstall_empty)
            return
        }
        massUninstallDialog?.dismiss()
        massUninstallDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_command_mass_uninstall)
            .setMessage(resources.getQuantityString(R.plurals.mass_uninstall_confirm, selected.size, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.app_command_uninstall) { _, _ ->
                val packages = selected.sortedBy { it.appPackage == requireContext().packageName }
                    .map(AppModel.App::appPackage)
                if (!MassUninstallActivity.start(requireContext(), packages))
                    requireContext().showToast(R.string.unable_to_open_app)
            }
            .show()
    }

    private fun showAppRename(app: AppModel) {
        val identifier = when (app) {
            is AppModel.App -> app.appPackage
            is AppModel.PinnedShortcut -> app.identity
            is AppModel.PrivateSpaceHeader -> return
        }
        renameDialog?.dismiss()
        val input = androidx.appcompat.widget.AppCompatEditText(requireContext()).apply {
            setText(app.appLabel)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            contentDescription = getString(R.string.rename)
        }
        renameDialog = requireContext().createDialog(
            title = R.string.rename, action = R.string.save,
            validate = {
                val valid = input.text?.isNotBlank() == true
                if (!valid) input.error = getString(R.string.app_command_name_required)
                valid
            },
            onAction = {
                prefs.setAppRenameLabel(identifier, input.text.toString().trim())
                viewModel.getAppList()
            },
            content = { input },
        ).also { it.showRespectingStatusBar() }
        input.requestFocus()
        renameDialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun updateCommandIndicator() {
        val parsed = ContactCommandQuery.parse(currentQuery())
            ?.takeIf { prefs.contactCommandSettings().showIndicator }
        binding.commandIndicator.text = null
        binding.commandIndicator.setCompoundDrawablesRelative(null, null, null, null)
        binding.commandIndicator.isVisible = false
        val editable = searchTextView?.text as? Spannable
        if (parsed?.command == displayedCommand && (parsed == null || commandCodeIconSpan?.let {
                editable?.getSpanStart(it) == 0 && editable.getSpanEnd(it) == 1
            } == true)
        ) return
        displayedCommand = parsed?.command
        commandCodeIconSpan?.let { editable?.removeSpan(it) }
        commandCodeIconSpan = null
        if (parsed == null || editable.isNullOrEmpty()) return
        val icon = when (parsed?.command) {
            ContactCommand.PHONE -> R.drawable.ic_call
            ContactCommand.MESSAGE -> R.drawable.ic_message
            ContactCommand.WHATSAPP -> R.drawable.ic_whatsapp
            ContactCommand.CONTACT -> R.drawable.ic_person
            null -> null
        }
        val drawable = icon?.let { ContextCompat.getDrawable(requireContext(), it)?.mutate() }?.apply {
            val size = (18 * resources.displayMetrics.density).toInt()
            setBounds(0, 0, size, size)
            setTint(android.graphics.Color.WHITE)
        }
        if (drawable != null) {
            ImageSpan(drawable, ImageSpan.ALIGN_BASELINE).also { span ->
                editable.setSpan(span, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                commandCodeIconSpan = span
            }
        }
    }

    private fun scheduleContactCommand(query: String, items: List<SearchItem>) {
        commandJob?.cancel()
        val matches = items.filterIsInstance<SearchItem.ContactItem>()
        val settings = prefs.contactCommandSettings()
        if (!commandSession.automaticCandidate(
                settings, matches.size, viewModel.contactsLoading.value == true,
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), isSearchComposing()
            ) || !ContactsLoader.hasPermission(requireContext())
        ) return
        val contact = matches.single().contact
        if (ContactCommandQuery.parse(query)?.command != ContactCommand.CONTACT && contact.number.isNullOrBlank()) return
        commandJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(settings.delayMillis.toLong())
            if (query == currentQuery() && commandSession.automaticCandidate(
                    prefs.contactCommandSettings(), matches.size, viewModel.contactsLoading.value == true,
                    viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), isSearchComposing()
                ) && ContactsLoader.hasPermission(requireContext())
            ) executeContactCommand(contact, query)
        }
    }

    private fun executeContactCommand(contact: Contact, query: String = currentQuery()) {
        commandJob?.cancel()
        val parsed = ContactCommandQuery.parse(query) ?: return
        if (query != currentQuery() || viewModel.contactsLoading.value == true ||
            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!ContactsLoader.hasPermission(requireContext())) {
            requireContext().showToast(R.string.contacts_permission_denied)
            return
        }
        if (!AppSearchMatcher(parsed.name).matches(contact.name)) return
        if (!commandSession.claim(query)) return
        activePopupWindow?.dismiss()
        commandExecutor?.execute(contact, parsed.command)
    }

    private fun cancelAutomaticCommand() {
        if (mode != SearchMode.CONTACTS_ONLY) return
        commandJob?.cancel()
        commandExecutor?.cancel()
        commandSubmitQuery = null
        if (ContactCommandQuery.parse(currentQuery()) != null) commandSession.consumed = true
    }

    private class Titles(val apps: String, val contacts: String, val search: String, val permission: String,
        val messagesPermission: String)

    private suspend fun buildItems(
        query: String,
        apps: List<AppModel>,
        contacts: List<Contact>,
        engines: List<SearchEngine>,
        contactsGranted: Boolean,
        titles: Titles,
        groups: List<GroupChat>,
        messagesGranted: Boolean,
    ): List<SearchItem> {
        val commandQuery = if (mode == SearchMode.CONTACTS_ONLY) ContactCommandQuery.parse(query) else null
        val appQuery = if (mode == SearchMode.APPS_ONLY) AppCommandQuery.parse(query) else null
        val engineCommand = if (mode == SearchMode.ENGINES_ONLY) SearchEngines.SearchEngineCommandQuery.parse(query) else null
        val q = appQuery?.name ?: commandQuery?.name ?: engineCommand?.name ?: query.trim()
        val matcher = AppSearchMatcher(q)
        val coroutineContext = currentCoroutineContext()
        val showHeaders = mode == SearchMode.ALL_SEARCH
        val items = mutableListOf<SearchItem>()

        if (mode.showsApps) {
            val matches = if (q.isEmpty()) apps else apps.filter {
                coroutineContext.ensureActive()
                matcher.matches(it.appLabel)
            }
            if (matches.isNotEmpty()) {
                if (showHeaders) items.add(SearchItem.Header(titles.apps))
                matches.mapTo(items) { SearchItem.App(it) }
            }
        }

        if (mode.showsContacts) {
            if (!contactsGranted) {
                if (showHeaders) items.add(SearchItem.Header(titles.contacts))
                items.add(SearchItem.Permission(titles.permission))
            } else {
                val isNumberQuery = commandQuery == null && q.length >= 3 && q.all { it.isDigit() || it in "+-() " }
                val digits = q.filter { it.isDigit() }
                val matches = when {
                    q.isEmpty() -> contacts
                    isNumberQuery -> contacts.filter {
                        coroutineContext.ensureActive()
                        it.number?.filter(Char::isDigit)?.contains(digits) == true
                    }
                    else -> contacts.filter {
                        coroutineContext.ensureActive()
                        matcher.matches(it.name)
                    }
                }
                if (matches.isNotEmpty()) {
                    if (showHeaders) items.add(SearchItem.Header(titles.contacts))
                    matches.mapTo(items) { SearchItem.ContactItem(it) }
                }
            }
            if (commandQuery == null) {
                groups.filter {
                    coroutineContext.ensureActive()
                    messagesGranted && matcher.matches(it.name)
                }.mapTo(items) { SearchItem.GroupChatItem(it) }
                if (!messagesGranted && q.isEmpty())
                    items.add(SearchItem.Permission(titles.messagesPermission, messages = true))
            }
        }

        if (mode.showsEngines && engines.isNotEmpty()) {
            val matchingEngines = when {
                engineCommand?.name?.isNotEmpty() == true -> engines.filter {
                    coroutineContext.ensureActive()
                    matcher.matches(it.name)
                }
                engineCommand != null -> engines
                else -> engines
            }
            val aliasEngineId = if (engineCommand == null) SearchEngines.parseAliasQuery(engines, query)?.first?.id else null
            if (matchingEngines.isNotEmpty()) {
                if (showHeaders) items.add(SearchItem.Header(titles.search))
                matchingEngines.mapTo(items) { SearchItem.Engine(it, it.id == aliasEngineId) }
            }
        }
        if (mode == SearchMode.CONTACTS_ONLY && q.isNotBlank()) items.add(SearchItem.AddContact(q))
        return items
    }

    private fun submit(text: String) {
        if (mode == SearchMode.CONTACTS_ONLY) {
            val letter = text.trim().singleOrNull()?.lowercaseChar()
            ContactCommand.entries.firstOrNull { it.letter == letter }?.let { command ->
                cancelAutomaticCommand()
                activePopupWindow?.dismiss()
                commandExecutor?.openApp(command)
                return
            }
        }
        if (mode == SearchMode.ENGINES_ONLY && SearchEngines.SearchEngineCommandQuery.parse(text) != null) return
        if (mode == SearchMode.APPS_ONLY && AppCommandQuery.parse(text) != null) {
            appCommandJob?.cancel()
            adapter.currentList.filterIsInstance<SearchItem.App>().singleOrNull()?.let { executeAppCommand(it.app, text) }
            return
        }
        if (mode == SearchMode.CONTACTS_ONLY && ContactCommandQuery.parse(text) != null) {
            commandJob?.cancel()
            commandSubmitQuery = text
            rebuild()
            return
        }
        val query = text.trim()
        if (mode.showsEngines) {
            SearchEngines.parseAliasQuery(engines, text)?.let { (engine, rest) ->
                if (rest.isBlank()) requireContext().showToast(R.string.type_search_query)
                else openEngine(engine, rest)
                return
            }
        }
        if (mode == SearchMode.ENGINES_ONLY) {
            if (query.isEmpty()) requireContext().showToast(R.string.type_search_query)
            else engines.firstOrNull()?.let { openEngine(it, query) }
            return
        }
        when (val first = adapter.currentList.firstOrNull {
            it is SearchItem.App || it is SearchItem.ContactItem || it is SearchItem.GroupChatItem
        }) {
            is SearchItem.App -> onAppClick(first.app)
            is SearchItem.ContactItem -> onContactClick(first.contact)
            is SearchItem.GroupChatItem -> onGroupChatClick(first.chat)
            else -> if (mode == SearchMode.ALL_SEARCH && query.isNotEmpty())
                engines.firstOrNull()?.let { openEngine(it, query) }
        }
    }

    private fun openEngine(engine: SearchEngine, query: String) {
        if (SearchEngines.open(requireContext(), engine, query)) closeSearch()
    }

    private fun closeSearch() {
        findNavController().popBackStack(R.id.mainFragment, false)
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun requestContactsPermission() {
        permissionRequested = true
        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    // SearchActions

    override fun onAddContact(query: String) {
        cancelAutomaticCommand()
        val value = query.trim()
        val isPhone = value.any(Char::isDigit) && value.all { it.isDigit() || it in "+-(). /#*" }
        val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(if (isPhone) ContactsContract.Intents.Insert.PHONE else ContactsContract.Intents.Insert.NAME, value)
        }
        if (!startSafely(intent)) requireContext().showToast(R.string.unable_to_open_contact)
    }

    override fun onAppClick(app: AppModel) {
        if (mode == SearchMode.APPS_ONLY && AppCommandQuery.parse(currentQuery()) != null) {
            executeAppCommand(app)
            return
        }
        viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
        closeSearch()
    }

    override fun onAppLongClick(app: AppModel) {
        if (mode == SearchMode.APPS_ONLY) {
            appCommandJob?.cancel()
            if (AppCommandQuery.parse(currentQuery()) != null) appCommandSession.consumed = true
        }
        openAppInfo(requireContext(), app.user, app.appPackage)
    }

    override fun onResultMenu(item: SearchItem, anchor: View) {
        cancelAutomaticCommand()
        val entries: List<Pair<Int, () -> Unit>> = when (item) {
            is SearchItem.App -> listOf(R.string.search_app_info to { onAppLongClick(item.app) })
            is SearchItem.ContactItem -> buildList {
                if (!item.contact.number.isNullOrBlank()) {
                    add(R.string.call to { onContactCall(item.contact) })
                    add(R.string.text_message to { onContactText(item.contact) })
                    add(R.string.whatsapp to { onContactWhatsApp(item.contact) })
                }
                add(R.string.search_contact_details to { openContactDetails(item.contact) })
            }
            is SearchItem.Engine -> listOf(
                R.string.edit_search_engine to {
                    findNavController().navigate(R.id.searchEnginesFragment,
                        Bundle().apply { putString("edit_engine_id", item.engine.id) })
                },
            )
            else -> return
        }
        val density = resources.displayMetrics.density
        val menu = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(appAppearance?.popupColor ?: Color.rgb(32, 32, 32))
                cornerRadius = 16 * density
                setStroke(density.toInt().coerceAtLeast(1), Color.rgb(54, 54, 54))
            }
        }
        val popup = createPopup(menu, 256, R.drawable.bg_search_tile)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        entries.forEach { (label, action) ->
            menu.addView(TextView(requireContext()).apply {
                setText(label)
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (48 * density).toInt()
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                val color = if (appAppearance?.applyTileColorToMenu == true)
                    appAppearance?.tileColor else appAppearance?.menuTileColor
                background = searchTileSurface(context, color ?: appAppearance?.popupColor)
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        showPopup(popup, anchor, menu)
    }

    override fun onContactClick(contact: Contact) {
        if (mode == SearchMode.CONTACTS_ONLY && ContactCommandQuery.parse(currentQuery()) != null) {
            executeContactCommand(contact)
            return
        }
        openContactDetails(contact)
    }

    private fun openContactDetails(contact: Contact) {
        val uri = if (contact.lookupKey.isNotEmpty())
            ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)
        else ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.id)
        if (!startSafely(Intent(Intent.ACTION_VIEW, uri)))
            requireContext().showToast(R.string.unable_to_open_contact)
    }

    override fun onContactTileClick(contact: Contact, anchor: View) {
        if (mode == SearchMode.CONTACTS_ONLY && ContactCommandQuery.parse(currentQuery()) != null)
            executeContactCommand(contact)
        else showContactActions(contact, anchor)
    }

    override fun onContactLongClick(contact: Contact) {
        cancelAutomaticCommand()
        openContactDetails(contact)
    }

    override fun onContactCall(contact: Contact) {
        cancelAutomaticCommand()
        val number = contact.number ?: return requireContext().showToast(R.string.no_phone_number)
        if (!startSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))))
            requireContext().showToast(R.string.unable_to_open_app)
    }

    override fun onContactText(contact: Contact) {
        cancelAutomaticCommand()
        val number = contact.number ?: return requireContext().showToast(R.string.no_phone_number)
        if (!startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))))
            requireContext().showToast(R.string.unable_to_open_app)
    }

    override fun onContactWhatsApp(contact: Contact) {
        cancelAutomaticCommand()
        val digits = (contact.normalizedNumber ?: contact.number)?.filter { it.isDigit() }
        if (digits.isNullOrEmpty()) return requireContext().showToast(R.string.no_phone_number)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits")
        val opened = WHATSAPP_PACKAGES.any { startSafely(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }
        if (!opened) requireContext().showToast(R.string.whatsapp_not_installed)
    }

    override fun onEngineClick(engine: SearchEngine) {
        val text = currentQuery()
        if (mode == SearchMode.ENGINES_ONLY && SearchEngines.SearchEngineCommandQuery.parse(text) != null) {
            findNavController().navigate(R.id.searchEnginesFragment,
                Bundle().apply { putString("edit_engine_id", engine.id) })
            return
        }
        val query = (SearchEngines.parseAliasQuery(engines, text)?.second ?: text).trim()
        if (query.isEmpty()) {
            binding.search.setQuery("${engine.alias} ", false)
            // showKeyboard() toggles the IME, which would close an already open keyboard
            binding.search.findViewById<TextView>(R.id.search_src_text)?.let { field ->
                field.requestFocus()
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
            return
        }
        openEngine(engine, query)
    }

    override fun onPermissionClick() {
        if (permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS))
            openAppInfo(requireContext(), android.os.Process.myUserHandle(), requireContext().packageName)
        else
            requestContactsPermission()
    }

    override fun onMessagesPermissionClick() {
        if (messagesPermissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
            openAppInfo(requireContext(), android.os.Process.myUserHandle(), requireContext().packageName)
        } else {
            messagesPermissionRequested = true
            messagesPermission.launch(Manifest.permission.READ_SMS)
        }
    }

    override fun onGroupChatClick(chat: GroupChat) {
        cancelAutomaticCommand()
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, chat.threadId))
        Telephony.Sms.getDefaultSmsPackage(requireContext())?.let(intent::setPackage)
        if (!startSafely(intent)) {
            val composer = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", chat.addresses.joinToString(";"), null))
            intent.`package`?.let(composer::setPackage)
            if (!startSafely(composer)) requireContext().showToast(R.string.unable_to_open_app)
        }
    }

    private fun registerMessagesObserver() {
        if (!mode.showsContacts || messagesObserverRegistered || !GroupChatsLoader.hasPermission(requireContext())) return
        requireContext().contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, messagesObserver)
        messagesObserverRegistered = true
    }

    override fun onStart() {
        super.onStart()
        if (mode.showsContacts) {
            requireContext().contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
            registerMessagesObserver()
            viewModel.loadContacts()
        }
        searchTextView?.showKeyboard() ?: binding.search.showKeyboard()
    }

    override fun onResume() {
        super.onResume()
        if (mode == SearchMode.CONTACTS_ONLY || mode == SearchMode.APPS_ONLY) rebuild()
    }

    override fun onPause() {
        if (mode == SearchMode.APPS_ONLY) {
            appCommandJob?.cancel()
            if (AppCommandQuery.parse(currentQuery()) != null) appCommandSession.consumed = true
        }
        if (mode == SearchMode.CONTACTS_ONLY) {
            cancelAutomaticCommand()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("appCommandQuery", appCommandSession.query)
        outState.putBoolean("appCommandConsumed", appCommandSession.consumed)
        outState.putStringArrayList("massUninstallSelection", ArrayList(massUninstallSelection.keys))
        outState.putString("contactCommandQuery", commandSession.query)
        outState.putBoolean("contactCommandConsumed", commandSession.consumed)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        contactsRefreshJob?.cancel()
        messagesRefreshJob?.cancel()
        filterJob?.cancel()
        if (mode.showsContacts)
            requireContext().contentResolver.unregisterContentObserver(contactsObserver)
        if (messagesObserverRegistered) {
            requireContext().contentResolver.unregisterContentObserver(messagesObserver)
            messagesObserverRegistered = false
        }
        activePopupWindow?.dismiss()
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        appCommandJob?.cancel()
        renameDialog?.dismiss()
        renameDialog = null
        massUninstallDialog?.dismiss()
        massUninstallDialog = null
        appCommandChip = null
        commandJob?.cancel()
        commandExecutor?.cancel()
        commandExecutor = null
        filterJob?.cancel()
        activePopupWindow?.dismiss()
        activePopupWindow = null
        popupBackCallback.isEnabled = false
        searchTextView = null
        if (::adapter.isInitialized) adapter.close()
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
