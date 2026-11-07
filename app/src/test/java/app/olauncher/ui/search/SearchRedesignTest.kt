package app.olauncher.ui.search

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.navigation.Navigation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchRedesignTest {
    private val app = AppModel.App("Chrome", null, "com.android.chrome", null, user = Process.myUserHandle())
    private lateinit var pageController: org.robolectric.android.controller.ActivityController<ContactCommandTestActivity>

    private fun withPage(mode: SearchMode, columns: Int = 1,
        action: (ContactCommandTestActivity, UniversalSearchFragment, MainViewModel) -> Unit,
    ) {
        CommandContactsProvider().install()
        val controller = Robolectric.buildActivity(ContactCommandTestActivity::class.java).setup()
        pageController = controller
        try {
            val activity = controller.get()
            Prefs(activity).apply {
                setSearchTileCount(mode, columns)
                setContactCommandSettings(ContactCommandSettings(automatic = false))
            }
            SearchEngines.save(activity, listOf(SearchEngine("test", "Google", "https://google.com/search?q=%s", "g")))
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]
            vm.appList.value = listOf(app)
            val fragment = UniversalSearchFragment().apply {
                arguments = Bundle().apply { putString(Constants.Key.SEARCH_MODE, mode.name) }
            }
            activity.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            awaitCommand { vm.contactsLoading.value == false }
            val expected = (if (mode.showsApps) 1 else 0) + (if (mode.showsContacts) 2 else 0) + (if (mode.showsEngines) 1 else 0)
            awaitCommand { count(fragment) == "$expected results" || (expected == 1 && count(fragment) == "1 result") }
            layout(fragment)
            action(activity, fragment, vm)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun count(fragment: UniversalSearchFragment) =
        fragment.requireView().findViewById<TextView>(R.id.resultsCount).text.toString()

    private fun awaitAppCommandScheduled(fragment: UniversalSearchFragment) {
        val field = UniversalSearchFragment::class.java.getDeclaredField("appCommandJob").apply { isAccessible = true }
        awaitCommand { (field.get(fragment) as? kotlinx.coroutines.Job)?.isActive == true }
    }

    private fun layout(fragment: UniversalSearchFragment, width: Int = 400, height: Int = 800, idle: Boolean = true) {
        val density = fragment.resources.displayMetrics.density
        fragment.requireView().apply {
            val w = (width * density).toInt()
            val h = (height * density).toInt()
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }
        if (idle) shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `one column contact shortcut icons are white`() {
        withPage(SearchMode.CONTACTS_ONLY) { _, fragment, _ ->
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as SearchResultsAdapter
            for ((position, item) in adapter.currentList.withIndex()) {
                if (item !is SearchItem.ContactItem) continue
                val row = list.findViewHolderForAdapterPosition(position)!!.itemView
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.contactActions).visibility)
                for (id in listOf(R.id.contactCallAction, R.id.contactTextAction, R.id.contactWhatsAppAction)) {
                    assertEquals(android.graphics.Color.WHITE,
                        row.findViewById<android.widget.ImageButton>(id).imageTintList?.defaultColor)
                }
            }
        }
    }

    @Test
    fun `all pages have one pill field fixed heading counter and consistently labelled tiles in both layouts`() {
        val titles = listOf("Apps", "Contacts", "Search Engines", "Search Anything")
        for ((index, mode) in SearchMode.entries.withIndex()) for (columns in 1..2) {
            withPage(mode, columns) { _, fragment, _ ->
                val root = fragment.requireView()
                assertEquals(
                    androidx.core.content.ContextCompat.getColor(root.context, R.color.search_background),
                    (root.background as ColorDrawable).color,
                )
                assertEquals(titles[index], root.findViewById<TextView>(R.id.modeTitle).text.toString())
                assertEquals("", root.findViewById<SearchView>(R.id.search).queryHint.toString())
                val editor = root.findViewById<TextView>(R.id.search_src_text)
                assertEquals(
                    android.view.Gravity.CENTER_VERTICAL,
                    editor.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK,
                )
                val list = root.findViewById<RecyclerView>(R.id.recyclerView)
                assertEquals(columns, (list.layoutManager as GridLayoutManager).spanCount)
                val adapter = list.adapter as SearchResultsAdapter
                for ((position, item) in adapter.currentList.withIndex()) {
                    val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: continue
                    if (item is SearchItem.Header || item is SearchItem.Permission) continue
                    assertEquals(
                        if (item is SearchItem.ContactItem || (item is SearchItem.App && mode == SearchMode.APPS_ONLY) ||
                            (item is SearchItem.Engine && mode == SearchMode.ENGINES_ONLY)
                        )
                            View.GONE else View.VISIBLE,
                        row.findViewById<View>(R.id.itemMenu).visibility,
                    )
                    assertEquals(
                        if (item is SearchItem.ContactItem && columns == 1) View.VISIBLE else View.GONE,
                        row.findViewById<View>(R.id.contactActions).visibility,
                    )
                    val label = when (item) {
                        is SearchItem.App -> "App"
                        is SearchItem.Engine -> "Search Engine"
                        is SearchItem.ContactItem -> null
                        else -> error("Unexpected row")
                    }
                    val itemType = row.findViewById<TextView>(R.id.itemType)
                    if (label == null) assertEquals(View.GONE, itemType.visibility)
                    else assertEquals(label, itemType.text.toString())
                    assertTrue(row.findViewById<View>(R.id.itemTitle).width > 0)
                    assertTrue(row.findViewById<View>(R.id.itemIcon).width > 0)
                }
            }
        }
    }

    @Test
    fun `contact counts and empty state update and loading does not show a false empty state`() {
        withPage(SearchMode.CONTACTS_ONLY) { _, fragment, vm ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            search.setQuery("john", false)
            awaitCommand { count(fragment) == "1 result" }
            search.setQuery("not present", false)
            awaitCommand { count(fragment) == "0 results" }
            assertEquals(View.GONE, root.findViewById<View>(R.id.emptyState).visibility)
            assertEquals("No contacts found", root.findViewById<TextView>(R.id.emptyMessage).text.toString())
            vm.contactsLoading.value = true
            awaitCommand { root.findViewById<View>(R.id.emptyState).visibility == View.GONE }
            vm.contacts.value = listOf(Contact(3, "new", "Not Present", null, null))
            vm.contactsLoading.value = false
            awaitCommand { count(fragment) == "1 result" }
            assertEquals(View.GONE, root.findViewById<View>(R.id.emptyState).visibility)
        }
    }

    @Test
    fun `all search count excludes headings and permission rows and observes engine and app changes`() {
        withPage(SearchMode.ALL_SEARCH) { activity, fragment, vm ->
            assertEquals("4 results", count(fragment))
            vm.appList.value = listOf(app, app.copy(appLabel = "Calendar", appPackage = "calendar"))
            awaitCommand { count(fragment) == "5 results" }
            SearchEngines.save(activity, emptyList())
            awaitCommand { count(fragment) == "4 results" }
            shadowOf(activity.application).denyPermissions(android.Manifest.permission.READ_CONTACTS)
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery(" ", false)
            awaitCommand { count(fragment) == "2 results" }
        }
    }

    @Test
    fun `heading stays fixed while results scroll and keyboard insets change`() {
        withPage(SearchMode.CONTACTS_ONLY) { _, fragment, vm ->
            vm.contacts.value = (1..100).map { Contact(it.toLong(), "$it", "Person $it", null, null) }
            awaitCommand { count(fragment) == "100 results" }
            layout(fragment)
            val root = fragment.requireView()
            val heading = root.findViewById<View>(R.id.searchHeading)
            val top = heading.top
            val list = root.findViewById<RecyclerView>(R.id.recyclerView)
            list.scrollBy(0, 1000)
            layout(fragment)
            assertEquals(top, heading.top)
            val inset = (250 * fragment.resources.displayMetrics.density).toInt()
            val keyboard = WindowInsetsCompat.Builder()
                .setSystemWindowInsets(Insets.of(0, 0, 0, inset))
                .setStableInsets(Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, inset)).build()
            assertEquals(inset, keyboard.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            // Pre-30 platform WindowInsets cannot carry synthetic IME type overrides.
            val listener = root.getTag(androidx.core.R.id.tag_on_apply_window_listener) as
                androidx.core.view.OnApplyWindowInsetsListener
            listener.onApplyWindowInsets(root, keyboard)
            assertEquals(inset, root.paddingBottom)
            layout(fragment, idle = false)
            assertEquals(top, heading.top)
            assertEquals(inset, root.paddingBottom)
        }
    }

    @Test
    fun `result menu does not launch contact is scrollable and dismisses on Back without scrolling`() {
        withPage(SearchMode.CONTACTS_ONLY) { activity, fragment, _ ->
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            val tile = list.getChildAt(0)
            tile.findViewById<View>(R.id.itemMenu).performClick()
            val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
            val popup = field.get(fragment) as PopupWindow
            assertTrue(popup.isShowing)
            assertTrue(popup.contentView is ScrollView)
            assertNull(shadowOf(activity).peekNextStartedActivity())
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(popup.isShowing)
            assertEquals(0, (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
        }
    }

    @Test
    fun `search page popup settings remain on engines and page appearance is hidden`() {
        for (mode in SearchMode.entries) withPage(mode) { _, fragment, _ ->
            fragment.requireView().findViewById<View>(R.id.searchPageMenu).performClick()
            val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply {
                isAccessible = true
            }
            val popup = field.get(fragment) as PopupWindow
            val menu = popup.contentView
            val firstRow = (menu as android.view.ViewGroup).getChildAt(0)
            assertNotNull(firstRow.findViewById<View>(R.id.tileCount))
            val settingsVisibility = menu.findViewById<View>(R.id.searchPageSettings).visibility
            val appearanceVisibility = menu.findViewById<View>(R.id.searchPageAppearance).visibility
            assertEquals(
                if (mode == SearchMode.ENGINES_ONLY) View.VISIBLE else View.GONE,
                settingsVisibility,
            )
            assertEquals(
                View.GONE,
                appearanceVisibility,
            )
            popup.dismiss()
            fragment.requireView().findViewById<View>(R.id.searchPageMenu).performClick()
            val reopened = field.get(fragment) as PopupWindow
            assertNotSame(popup, reopened)
            assertTrue(reopened.isShowing)
            reopened.dismiss()
        }
    }

    @Test
    fun `search engine result overflow does not contain search engine settings`() {
        withPage(SearchMode.ENGINES_ONLY) { activity, fragment, _ ->
            val row = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView).getChildAt(0)
            row.findViewById<View>(R.id.itemMenu).performClick()
            val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
            val popup = field.get(fragment) as PopupWindow
            val content = (popup.contentView as ScrollView).getChildAt(0) as android.widget.LinearLayout
            assertEquals(1, content.childCount)
            assertEquals(activity.getString(R.string.edit_search_engine), (content.getChildAt(0) as TextView).text)
            popup.dismiss()
        }
    }

    @Test
    fun `engine edit command filters engines and is available below page settings`() {
        withPage(SearchMode.ENGINES_ONLY) { activity, fragment, _ ->
            SearchEngines.save(activity, listOf(
                SearchEngine("google", "Google", "https://google.com/search?q=%s", "g"),
                SearchEngine("youtube", "YouTube", "https://youtube.com/results?q=%s", "yt"),
            ))
            val search = fragment.requireView().findViewById<SearchView>(R.id.search)
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as SearchResultsAdapter
            search.setQuery("e goo", false)
            awaitCommand { adapter.currentList.filterIsInstance<SearchItem.Engine>().map { it.engine.id } == listOf("google") }
            assertEquals(1, adapter.currentList.filterIsInstance<SearchItem.Engine>().size)
            layout(fragment)
            val editor = fragment.requireView().findViewById<TextView>(R.id.search_src_text).text as android.text.Spanned
            assertEquals(1, editor.getSpans(0, 1, EngineEditChipSpan::class.java).size)

            fragment.requireView().findViewById<View>(R.id.searchPageMenu).performClick()
            val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
            val popup = field.get(fragment) as PopupWindow
            val menu = (popup.contentView as ScrollView).getChildAt(0) as android.view.ViewGroup
            assertNotNull(menu.getChildAt(0).findViewById<View>(R.id.tileCount))
            assertEquals(activity.getString(R.string.search_page_settings),
                menu.getChildAt(1).findViewById<TextView>(R.id.searchPageSettings).text)
            val commands = popup.contentView.findViewById<android.widget.LinearLayout>(R.id.contactCommands)
            assertEquals(1, commands.childCount)
            val edit = commands.getChildAt(0) as TextView
            assertEquals("E - Edit", edit.text.toString())
            assertNotNull(edit.compoundDrawablesRelative[0])
            edit.performClick()
            assertEquals("e goo", search.query.toString())
            assertEquals(1, (fragment.requireView().findViewById<TextView>(R.id.search_src_text).text as android.text.Spanned)
                .getSpans(0, 1, EngineEditChipSpan::class.java).size)
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `tapping filtered engine in E edit mode opens that engine editor`() {
        withPage(SearchMode.ENGINES_ONLY) { activity, fragment, _ ->
            SearchEngines.save(activity, listOf(
                SearchEngine("google", "Google", "https://google.com/search?q=%s", "g"),
                SearchEngine("youtube", "YouTube", "https://youtube.com/results?q=%s", "yt"),
            ))
            val search = fragment.requireView().findViewById<SearchView>(R.id.search)
            val adapter = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView).adapter as SearchResultsAdapter
            search.setQuery("e goo", false)
            awaitCommand { adapter.currentList.filterIsInstance<SearchItem.Engine>().map { it.engine.id } == listOf("google") }
            layout(fragment)
            val navController = RecordingNavController(activity)
            Navigation.setViewNavController(fragment.requireView(), navController)
            fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView).getChildAt(0).performClick()
            assertEquals(R.id.searchEnginesFragment, navController.destination)
            assertEquals("google", navController.arguments?.getString("edit_engine_id"))
        }
    }

    @Test
    fun `popup opens down or up within keyboard safe bounds and caps oversized content`() {
        val visible = Rect(0, 24, 400, 500)
        assertEquals(Rect(100, 100, 356, 300), searchPopupBounds(visible, 308, 52, 48, 48, 256, 200))
        assertEquals(Rect(100, 230, 356, 430), searchPopupBounds(visible, 308, 430, 48, 48, 256, 200))
        val tall = searchPopupBounds(visible, 390, 430, 48, 48, 600, 1000)
        assertTrue(visible.contains(tall))
        assertEquals(400, tall.width())
        assertEquals(406, tall.height())
    }

    @Test
    fun `app and engine empty states remove themselves as data arrives`() {
        withPage(SearchMode.APPS_ONLY) { _, fragment, vm ->
            vm.appList.value = emptyList()
            awaitCommand { count(fragment) == "0 results" }
            assertEquals("No apps found", fragment.requireView().findViewById<TextView>(R.id.emptyMessage).text.toString())
            assertEquals(View.VISIBLE, fragment.requireView().findViewById<View>(R.id.emptyState).visibility)
            vm.appList.value = listOf(app)
            awaitCommand { count(fragment) == "1 result" }
            assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.emptyState).visibility)
        }
        withPage(SearchMode.ENGINES_ONLY) { activity, fragment, _ ->
            SearchEngines.save(activity, emptyList())
            awaitCommand { count(fragment) == "0 results" }
            assertEquals("No search engines found", fragment.requireView().findViewById<TextView>(R.id.emptyMessage).text.toString())
            assertEquals(View.VISIBLE, fragment.requireView().findViewById<View>(R.id.emptyState).visibility)
        }
    }

    @Test
    fun `contact action menu dispatches the selected action instead of opening details`() {
        withPage(SearchMode.CONTACTS_ONLY) { activity, fragment, _ ->
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            list.getChildAt(0).findViewById<View>(R.id.itemMenu).performClick()
            val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
            val popup = field.get(fragment) as PopupWindow
            val menu = (popup.contentView as ScrollView).getChildAt(0) as android.widget.LinearLayout
            val call = (0 until menu.childCount).map { menu.getChildAt(it) as TextView }
                .single { it.text == activity.getString(R.string.call) }
            call.performClick()
            assertEquals(android.content.Intent.ACTION_DIAL, shadowOf(activity).nextStartedActivity.action)
            assertFalse(popup.isShowing)
        }
    }

    @Test
    fun `contact command prefixes use inline icons without a heading label`() {
        withPage(SearchMode.CONTACTS_ONLY) { _, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            val editor = root.findViewById<TextView>(R.id.search_src_text)
            for (prefix in listOf("p", "m", "w", "c")) {
                search.setQuery("$prefix ", false)
                awaitCommand { editor.text.toString() == "$prefix " }
                assertEquals("$prefix ", editor.text.toString())
                assertEquals(
                    1,
                    (editor.text as android.text.Spanned).getSpans(0, 1, android.text.style.ImageSpan::class.java).size,
                )
                assertEquals(View.GONE, root.findViewById<View>(R.id.commandIndicator).visibility)
            }
        }
    }

    @Test
    fun `app command menu uses ordered spaced entries and editable chips in both layouts`() {
        for (columns in 1..2) withPage(SearchMode.APPS_ONLY, columns) { activity, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            val list = root.findViewById<RecyclerView>(R.id.recyclerView)
            assertEquals(View.GONE, list.getChildAt(0).findViewById<View>(R.id.itemMenu).visibility)
            val labels = listOf("R - Rename", "I - Open app info", "P - Go to Play Store", "U - Uninstall", "MU - Mass uninstall")
            for (command in AppCommand.entries) {
                root.findViewById<View>(R.id.searchPageMenu).performClick()
                val popupField = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
                val popup = popupField.get(fragment) as PopupWindow
                val group = popup.contentView.findViewById<android.widget.LinearLayout>(R.id.contactCommands)
                assertNotNull((popup.contentView as android.view.ViewGroup).getChildAt(0).findViewById<View>(R.id.tileCount))
                val gap = fragment.resources.getDimensionPixelSize(R.dimen.search_menu_item_gap)
                assertEquals(5, group.childCount)
                for (index in labels.indices) {
                    val entry = group.getChildAt(index) as TextView
                    assertEquals(labels[index], entry.text.toString())
                    assertNotNull(entry.compoundDrawablesRelative[0])
                    assertEquals(if (index == 0) 0 else gap,
                        (entry.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
                }
                group.getChildAt(AppCommand.entries.indexOf(command)).performClick()
                assertEquals("${command.code} ", search.query.toString())
                val editor = root.findViewById<TextView>(R.id.search_src_text)
                val text = editor.text as android.text.Spanned
                assertEquals(1, text.getSpans(0, 1, AppCommandChipSpan::class.java).size)
                assertNull(shadowOf(activity).peekNextStartedActivity())
            }
            val editor = root.findViewById<android.widget.EditText>(R.id.search_src_text)
            editor.text.clear()
            assertTrue(editor.text.getSpans(0, editor.length(), AppCommandChipSpan::class.java).isEmpty())
            assertNull(AppCommandQuery.parse(search.query.toString()))
        }
    }

    @Test
    fun `mass uninstall selection persists across filtering and waits for explicit action`() {
        withPage(SearchMode.APPS_ONLY) { activity, fragment, vm ->
            val second = app.copy(appLabel = "Calendar", appPackage = "com.example.calendar")
            for (targetPackage in listOf(app.appPackage, second.appPackage)) {
                shadowOf(activity.packageManager).installPackage(android.content.pm.PackageInfo().apply {
                    packageName = targetPackage
                    applicationInfo = android.content.pm.ApplicationInfo().apply {
                        packageName = targetPackage
                        flags = 0
                    }
                })
            }
            vm.appList.value = listOf(app, second)
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            val list = root.findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as SearchResultsAdapter
            search.setQuery("mu ", false)
            awaitCommand { adapter.currentList.filterIsInstance<SearchItem.App>().size == 2 }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            layout(fragment)
            val selectedPosition = adapter.currentList.indexOfFirst {
                it is SearchItem.App && it.app.appPackage == app.appPackage
            }
            list.findViewHolderForAdapterPosition(selectedPosition)!!.itemView
                .findViewById<android.widget.CheckBox>(R.id.massUninstallCheck).performClick()
            val action = root.findViewById<TextView>(R.id.massUninstallAction)
            assertEquals(View.VISIBLE, action.visibility)
            assertEquals("Uninstall 1 selected", action.text.toString())

            search.setQuery("mu calendar", false)
            awaitCommand { adapter.currentList.filterIsInstance<SearchItem.App>().singleOrNull()?.app == second }
            layout(fragment)
            assertFalse(list.findViewHolderForAdapterPosition(0)!!.itemView
                .findViewById<android.widget.CheckBox>(R.id.massUninstallCheck).isChecked)

            search.setQuery("mu ", false)
            awaitCommand { adapter.currentList.filterIsInstance<SearchItem.App>().size == 2 }
            layout(fragment)
            val restoredPosition = adapter.currentList.indexOfFirst {
                it is SearchItem.App && it.app.appPackage == app.appPackage
            }
            assertTrue(list.findViewHolderForAdapterPosition(restoredPosition)!!.itemView
                .findViewById<android.widget.CheckBox>(R.id.massUninstallCheck).isChecked)
            val secondPosition = adapter.currentList.indexOfFirst {
                it is SearchItem.App && it.app.appPackage == second.appPackage
            }
            list.findViewHolderForAdapterPosition(secondPosition)!!.itemView
                .findViewById<android.widget.CheckBox>(R.id.massUninstallCheck).performClick()
            assertEquals("Uninstall 2 selected", action.text.toString())
            action.performClick()
            val dialogField = UniversalSearchFragment::class.java.getDeclaredField("massUninstallDialog").apply {
                isAccessible = true
            }
            val confirmation = dialogField.get(fragment) as androidx.appcompat.app.AlertDialog
            assertTrue(confirmation.isShowing)
            assertEquals("Uninstall 2 apps? Android will ask you to confirm each app.",
                confirmation.findViewById<TextView>(android.R.id.message)!!.text.toString())
            assertNull(shadowOf(activity).peekNextStartedActivity())
            (confirmation as androidx.appcompat.app.AlertDialog)
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
    }

    @Test
    fun `mass uninstall activity advances to the next package after Android confirms removal`() {
        withPage(SearchMode.APPS_ONLY) { activity, _, _ ->
            val packages = listOf("com.example.first", "com.example.second")
            packages.forEach { packageName ->
                shadowOf(activity.packageManager).installPackage(android.content.pm.PackageInfo().apply {
                    this.packageName = packageName
                    applicationInfo = android.content.pm.ApplicationInfo().apply {
                        this.packageName = packageName
                        flags = 0
                    }
                })
            }
            val queueIntent = Intent(activity, MassUninstallActivity::class.java).apply {
                putStringArrayListExtra("packages", ArrayList(packages))
            }
            val controller = Robolectric.buildActivity(MassUninstallActivity::class.java, queueIntent).setup()
            val queueActivity = controller.get()
            val firstRequest = shadowOf(queueActivity).nextStartedActivityForResult
            assertEquals(android.content.Intent.ACTION_DELETE, firstRequest.intent.action)
            assertEquals("package:${packages.first()}", firstRequest.intent.dataString)

            queueActivity.handleUninstallResult(android.app.Activity.RESULT_OK)
            val secondRequest = shadowOf(queueActivity).nextStartedActivityForResult
            assertEquals(android.content.Intent.ACTION_DELETE, secondRequest.intent.action)
            assertEquals("package:${packages.last()}", secondRequest.intent.dataString)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `unique app command opens store once and falls back when Play Store is missing`() {
        for (available in listOf(true, false)) withPage(SearchMode.APPS_ONLY) { activity, fragment, vm ->
            activity.playStoreAvailable = available
            val search = fragment.requireView().findViewById<SearchView>(R.id.search)
            search.setQuery("P chrome", false)
            awaitAppCommandScheduled(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(249))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
            assertEquals(if (available) "market://details?id=com.android.chrome"
                else "https://play.google.com/store/apps/details?id=com.android.chrome", intent.dataString)
            vm.appList.value = listOf(app)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            fragment.onAppClick(app)
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `rename command allows choosing an app before a unique match and saves only on confirmation`() {
        for (columns in 1..2) withPage(SearchMode.APPS_ONLY, columns) { activity, fragment, vm ->
            val other = app.copy(appLabel = "Chrome Beta", appPackage = "com.chrome.beta")
            val savedName = Prefs(activity).getAppRenameLabel(other.appPackage)
            vm.appList.value = listOf(app, other)
            val search = fragment.requireView().findViewById<SearchView>(R.id.search)
            search.setQuery("r ch", false)
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as SearchResultsAdapter
            awaitCommand { adapter.currentList.filterIsInstance<SearchItem.App>().size == 2 }
            layout(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            val field = UniversalSearchFragment::class.java.getDeclaredField("renameDialog").apply { isAccessible = true }
            assertNull(field.get(fragment))
            list.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
            val dialog = field.get(fragment) as app.olauncher.helper.OlDialog
            assertTrue(dialog.isShowing)
            val input = dialog.findViewById<android.widget.FrameLayout>(R.id.contentContainer)!!.getChildAt(0) as android.widget.EditText
            assertEquals("Chrome Beta", input.text.toString())
            input.setText("My Browser")
            assertEquals(savedName, Prefs(activity).getAppRenameLabel(other.appPackage))
            dialog.findViewById<View>(R.id.tvAction)!!.performClick()
            assertEquals("My Browser", Prefs(activity).getAppRenameLabel(other.appPackage))
            assertEquals("", Prefs(activity).getAppRenameLabel(app.appPackage))
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `uninstall command requests Android confirmation for the selected package`() {
        withPage(SearchMode.APPS_ONLY) { activity, fragment, _ ->
            shadowOf(activity.packageManager).installPackage(android.content.pm.PackageInfo().apply {
                packageName = app.appPackage
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = app.appPackage; flags = 0 }
            })
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery("u chrome", false)
            awaitAppCommandScheduled(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_DELETE, intent.action)
            assertEquals("package:com.android.chrome", intent.dataString)
            assertNotNull(activity.packageManager.getPackageInfo(app.appPackage, 0))
            assertTrue(fragment.isAdded)
            assertEquals("u chrome", fragment.requireView().findViewById<SearchView>(R.id.search).query.toString())
        }
    }

    @Test
    fun `app commands cannot fire during composition after query changes or after leaving`() {
        withPage(SearchMode.APPS_ONLY) { activity, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            val editor = root.findViewById<android.widget.EditText>(R.id.search_src_text)
            val adapter = root.findViewById<RecyclerView>(R.id.recyclerView).adapter as SearchResultsAdapter
            search.setQuery("p chrome", false)
            awaitAppCommandScheduled(fragment)
            android.view.inputmethod.BaseInputConnection.setComposingSpans(editor.text)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            android.view.inputmethod.BaseInputConnection.removeComposingSpans(editor.text)
            search.setQuery("p chr", false)
            awaitAppCommandScheduled(fragment)
            search.setQuery("no matching app", false)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            search.setQuery("p chrome", false)
            awaitAppCommandScheduled(fragment)
            pageController.pause().stop().start().resume().visible()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `executed app command does not repeat after recreation`() {
        withPage(SearchMode.APPS_ONLY) { activity, fragment, _ ->
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery("p chrome", false)
            awaitAppCommandScheduled(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            assertNotNull(shadowOf(activity).nextStartedActivity)
            pageController.recreate()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            val recreated = pageController.get()
            val restored = recreated.supportFragmentManager.findFragmentById(android.R.id.content)!!
            assertEquals("p chrome", restored.requireView().findViewById<SearchView>(R.id.search).query.toString())
            assertNull(shadowOf(recreated).peekNextStartedActivity())
        }
    }

    @Test
    @Config(shadows = [AppCommandLauncherShadow::class, AppCommandActivityShadow::class])
    fun `app info command targets the selected app and user profile`() {
        withPage(SearchMode.APPS_ONLY) { activity, fragment, vm ->
            val profile = android.os.UserHandle.getUserHandleForUid(1000000)
            vm.appList.value = listOf(app.copy(user = profile))
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery("i chrome", false)
            awaitAppCommandScheduled(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            val launcher = org.robolectric.shadow.api.Shadow.extract<AppCommandLauncherShadow>(
                activity.getSystemService(android.content.pm.LauncherApps::class.java))
            assertEquals(android.content.ComponentName(app.appPackage, "MainActivity"), launcher.detailsComponent)
            assertEquals(profile, launcher.detailsUser)
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    @Config(shadows = [AppCommandLauncherShadow::class, AppCommandActivityShadow::class])
    fun `uninstall guards system apps other profiles and pinned shortcuts`() {
        for (target in listOf("system", "profile", "shortcut")) withPage(SearchMode.APPS_ONLY) { activity, fragment, vm ->
            val launcher = org.robolectric.shadow.api.Shadow.extract<AppCommandLauncherShadow>(
                activity.getSystemService(android.content.pm.LauncherApps::class.java))
            launcher.systemApp = target == "system"
            launcher.detailsComponent = null
            launcher.detailsUser = null
            val selected = when (target) {
                "shortcut" -> AppModel.PinnedShortcut("Chrome", null, app.appPackage, "shortcut", user = app.user)
                "profile" -> app.copy(user = android.os.UserHandle.getUserHandleForUid(1000000))
                else -> app
            }
            vm.appList.value = listOf(selected)
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery("u chrome", false)
            awaitAppCommandScheduled(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            if (target == "shortcut") {
                assertNull(launcher.detailsComponent)
                assertEquals(activity.getString(R.string.app_command_unsupported),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            } else {
                assertEquals(selected.appPackage, launcher.detailsComponent?.packageName)
                assertEquals(selected.user, launcher.detailsUser)
            }
        }
    }

    @Test
    fun `unique rename opens a dialog automatically without saving or launching`() {
        withPage(SearchMode.APPS_ONLY) { activity, fragment, _ ->
            val before = Prefs(activity).getAppRenameLabel(app.appPackage)
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery("r chrome", false)
            awaitAppCommandScheduled(fragment)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            val field = UniversalSearchFragment::class.java.getDeclaredField("renameDialog").apply { isAccessible = true }
            val dialog = field.get(fragment) as app.olauncher.helper.OlDialog
            assertTrue(dialog.isShowing)
            assertEquals(before, Prefs(activity).getAppRenameLabel(app.appPackage))
            dialog.dismiss()
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    @Config(sdk = [28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `app command chips render inside the query field at narrow phone widths`() {
        withPage(SearchMode.APPS_ONLY) { _, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            for (width in listOf(320, 400)) for (command in AppCommand.entries) {
                search.setQuery("${command.code} ", false)
                layout(fragment, width = width)
                val editor = root.findViewById<TextView>(R.id.search_src_text)
                val text = editor.text as android.text.Spanned
                val chip = text.getSpans(0, 1, AppCommandChipSpan::class.java).single()
                assertEquals(command.code.length, text.getSpanEnd(chip))
                assertTrue(chip.drawable.bounds.width() < editor.width)
                assertTrue(chip.drawable.bounds.height() <= editor.height - editor.paddingTop - editor.paddingBottom)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                System.getenv("ACES_SCREENSHOT_DIR")?.let { directory ->
                    File(directory, "app-command-${command.letter}-$width.png").outputStream().use {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                image.recycle()
            }
        }
    }

    @Test
    fun `app command prefixes remain ordinary text in Search Anything`() {
        withPage(SearchMode.ALL_SEARCH) { activity, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            val adapter = root.findViewById<RecyclerView>(R.id.recyclerView).adapter as SearchResultsAdapter
            for (command in AppCommand.entries) {
                search.setQuery("${command.code} chrome", false)
                awaitCommand { adapter.currentList.none { it is SearchItem.App } }
                val text = root.findViewById<TextView>(R.id.search_src_text).text as android.text.Spanned
                assertTrue(text.getSpans(0, text.length, AppCommandChipSpan::class.java).isEmpty())
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
                assertNull(shadowOf(activity).peekNextStartedActivity())
            }
        }
    }

    @Test
    fun `add contact is last full width result and prefills names or numbers`() {
        for (columns in 1..2) withPage(SearchMode.CONTACTS_ONLY, columns) { activity, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            val list = root.findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as SearchResultsAdapter
            assertTrue(adapter.currentList.none { it is SearchItem.AddContact })
            for ((query, value) in listOf("john" to "john", "+1 (555) 456-7890" to "+1 (555) 456-7890", "m Alex New" to "Alex New")) {
                search.setQuery(query, false)
                awaitCommand { adapter.currentList.lastOrNull() == SearchItem.AddContact(value) }
                layout(fragment)
                val position = adapter.itemCount - 1
                assertEquals(columns, (list.layoutManager as GridLayoutManager).spanSizeLookup.getSpanSize(position))
                val row = list.findViewHolderForAdapterPosition(position)!!.itemView
                assertEquals("Add New Contact", row.findViewById<TextView>(R.id.itemTitle).text.toString())
                row.performClick()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(android.content.Intent.ACTION_INSERT, intent.action)
                assertEquals(android.provider.ContactsContract.Contacts.CONTENT_TYPE, intent.type)
                val number = query.startsWith("+")
                val extra = if (number) android.provider.ContactsContract.Intents.Insert.PHONE
                    else android.provider.ContactsContract.Intents.Insert.NAME
                assertEquals(value, intent.getStringExtra(extra))
                assertNull(intent.getStringExtra(if (number) android.provider.ContactsContract.Intents.Insert.NAME
                    else android.provider.ContactsContract.Intents.Insert.PHONE))
            }
            search.setQuery("m ", false)
            awaitCommand { adapter.currentList.none { it is SearchItem.AddContact } }
        }
    }

    @Test
    fun `contact menu commands have ordered icons and replace the prefix while retaining the name`() {
        withPage(SearchMode.CONTACTS_ONLY) { activity, fragment, _ ->
            val root = fragment.requireView()
            val search = root.findViewById<SearchView>(R.id.search)
            search.setQuery("john", false)
            val labels = listOf("P - Phone", "M - Message", "W - WhatsApp", "C - Contact page")
            for (command in ContactCommand.entries + ContactCommand.CONTACT) {
                val index = ContactCommand.entries.indexOf(command)
                root.findViewById<View>(R.id.searchPageMenu).performClick()
                val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
                val popup = field.get(fragment) as PopupWindow
                val group = popup.contentView.findViewById<android.widget.LinearLayout>(R.id.contactCommands)
                assertEquals(View.VISIBLE, group.visibility)
                assertEquals(4, group.childCount)
                assertNotNull((popup.contentView as android.view.ViewGroup).getChildAt(0).findViewById<View>(R.id.tileCount))
                val gap = fragment.resources.getDimensionPixelSize(R.dimen.search_menu_item_gap)
                assertTrue(gap > 0)
                for (entryIndex in labels.indices) {
                    val entry = group.getChildAt(entryIndex) as TextView
                    assertEquals(labels[entryIndex], entry.text.toString())
                    assertNotNull(entry.compoundDrawablesRelative[0])
                    assertEquals(if (entryIndex == 0) 0 else gap,
                        (entry.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
                }
                group.getChildAt(index).performClick()
                assertFalse(popup.isShowing)
                assertEquals("${command.letter} john", search.query.toString())
                val text = root.findViewById<TextView>(R.id.search_src_text).text as android.text.Spanned
                assertEquals(1, text.getSpans(0, 1, android.text.style.ImageSpan::class.java).size)
                assertNull(shadowOf(activity).peekNextStartedActivity())
            }
        }
    }

    @Test
    @Config(sdk = [28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `contact photo replaces the person icon with no tint and unreadable photos fall back`() {
        withPage(SearchMode.CONTACTS_ONLY) { activity, fragment, vm ->
            val photoUri = android.net.Uri.parse("content://com.android.contacts/photos/test")
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val bytes = java.io.ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            shadowOf(activity.contentResolver).registerInputStream(photoUri, java.io.ByteArrayInputStream(bytes))
            vm.contacts.value = listOf(Contact(1, "john", "John Smith", "+15551000", null, photoUri.toString()))
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            val adapter = list.adapter as SearchResultsAdapter
            awaitCommand { (adapter.currentList.firstOrNull() as? SearchItem.ContactItem)?.contact?.photoUri == photoUri.toString() }
            layout(fragment)
            val image = list.getChildAt(0).findViewById<android.widget.ImageView>(R.id.itemIcon)
            awaitCommand { image.drawable is androidx.core.graphics.drawable.RoundedBitmapDrawable }
            assertNull(image.imageTintList)
            val photo = image.drawable as androidx.core.graphics.drawable.RoundedBitmapDrawable
            assertTrue(photo.isCircular)
            val rendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            photo.setBounds(0, 0, 32, 32)
            photo.draw(Canvas(rendered))
            assertEquals(Color.TRANSPARENT, rendered.getPixel(0, 0))
            assertEquals(Color.RED, rendered.getPixel(16, 16))
            rendered.recycle()
            shadowOf(activity.contentResolver).registerInputStream(android.net.Uri.parse("content://missing/photo"),
                object : java.io.InputStream() {
                    override fun read(): Int = throw java.io.FileNotFoundException("Missing test photo")
                })
            vm.contacts.value = listOf(Contact(2, "jane", "Jane Doe", null, null, "content://missing/photo"))
            awaitCommand { (adapter.currentList.firstOrNull() as? SearchItem.ContactItem)?.contact?.id == 2L }
            layout(fragment)
            val fallback = list.getChildAt(0).findViewById<android.widget.ImageView>(R.id.itemIcon)
            assertNotNull(fallback.drawable)
            assertNotNull(fallback.imageTintList)
            bitmap.recycle()
        }
    }

    @Test
    fun `contact loader includes thumbnail uris for contacts with and without numbers`() {
        withPage(SearchMode.CONTACTS_ONLY) { activity, _, _ ->
            CommandContactsProvider().apply {
                people[0] = people[0].copy(photoUri = "content://com.android.contacts/photos/1")
                people.add(CommandContactsProvider.Person(3, "alex", "Alex New", "content://com.android.contacts/photos/3"))
                install()
            }
            val contacts = ContactsLoader.load(activity).associateBy { it.id }
            assertEquals("content://com.android.contacts/photos/1", contacts[1]?.photoUri)
            assertNull(contacts[2]?.photoUri)
            assertEquals("content://com.android.contacts/photos/3", contacts[3]?.photoUri)
            assertNull(contacts[3]?.number)
        }
    }

    @Test
    fun `single tile contact actions are inline and launch their existing actions`() {
        withPage(SearchMode.CONTACTS_ONLY, columns = 1) { activity, fragment, _ ->
            val row = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView).getChildAt(0)
            assertEquals(View.GONE, row.findViewById<View>(R.id.itemMenu).visibility)
            assertEquals(View.VISIBLE, row.findViewById<View>(R.id.contactActions).visibility)
            row.findViewById<View>(R.id.contactCallAction).performClick()
            assertEquals(android.content.Intent.ACTION_DIAL, shadowOf(activity).nextStartedActivity.action)
        }
    }

    @Test
    fun `two tile contact shortcuts open without scrollbar indicators`() {
        withPage(SearchMode.CONTACTS_ONLY, columns = 2) { _, fragment, _ ->
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            list.getChildAt(0).performClick()
            val field = UniversalSearchFragment::class.java.getDeclaredField("activePopupWindow").apply {
                isAccessible = true
            }
            val popup = field.get(fragment) as PopupWindow
            assertTrue(popup.isShowing)
            assertFalse(popup.contentView.isVerticalScrollBarEnabled)
            assertFalse(popup.contentView.isHorizontalScrollBarEnabled)
            for (action in listOf(R.id.contactCallAction, R.id.contactTextAction, R.id.contactWhatsAppAction)) {
                assertEquals(View.VISIBLE, popup.contentView.findViewById<View>(action).visibility)
            }
            popup.dismiss()
        }
    }

    @Test
    @Config(sdk = [28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `redesigned pages render black backgrounds and readable tiles at phone widths`() {
        for (mode in SearchMode.entries) withPage(mode) { _, fragment, _ ->
            for (columns in 1..2) {
                val root = fragment.requireView()
                val list = root.findViewById<RecyclerView>(R.id.recyclerView)
                (list.layoutManager as GridLayoutManager).spanCount = columns
                (list.adapter as SearchResultsAdapter).setTileCount(columns)
                list.adapter!!.notifyItemRangeChanged(0, list.adapter!!.itemCount)
                layout(fragment, width = 360)
                fun invalidate(view: View) {
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    view.invalidate()
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) invalidate(view.getChildAt(i))
                }
                invalidate(root)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                assertEquals(
                    androidx.core.content.ContextCompat.getColor(root.context, R.color.search_background),
                    image.getPixel(0, 0),
                )
                for (i in 0 until list.childCount) {
                    val row = list.getChildAt(i)
                    val title = row.findViewById<TextView>(R.id.itemTitle) ?: continue
                    assertTrue("Result names need usable width in both layouts", title.width >= 32 * root.resources.displayMetrics.density)
                    assertEquals(Color.WHITE, title.currentTextColor)
                    val itemType = row.findViewById<TextView>(R.id.itemType)
                    if (itemType.visibility == View.VISIBLE) assertTrue(itemType.height > 0)
                    val menu = row.findViewById<View>(R.id.itemMenu)
                    if (menu.visibility == View.VISIBLE)
                        assertTrue(menu.width >= 48 * root.resources.displayMetrics.density)
                    val contactActions = row.findViewById<View>(R.id.contactActions)
                    if (contactActions.visibility == View.VISIBLE)
                        assertTrue(contactActions.width >= 120 * root.resources.displayMetrics.density)
                }
                System.getenv("ACES_SCREENSHOT_DIR")?.let { directory ->
                    File(directory, "search-${mode.name.lowercase()}-$columns.png").outputStream().use {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }

                }
                image.recycle()
            }
        }
    }

    @Test
    fun `two tile contact action popup centers over its tile and stays within visible bounds`() {
        assertEquals(
            Rect(95, 312, 255, 368),
            centeredSearchPopupBounds(Rect(0, 0, 400, 800), 100, 300, 150, 80, 160, 56),
        )
        assertEquals(
            Rect(0, 0, 160, 56),
            centeredSearchPopupBounds(Rect(0, 0, 400, 800), 0, 0, 80, 40, 160, 56),
        )
    }
}

private class RecordingNavController(context: android.content.Context) : NavController(context) {
    var destination: Int = View.NO_ID
    var arguments: Bundle? = null

    override fun navigate(
        resId: Int,
        args: Bundle?,
        navOptions: NavOptions?,
        navigatorExtras: Navigator.Extras?,
    ) {
        destination = resId
        arguments = args
    }
}

@org.robolectric.annotation.Implements(android.content.pm.LauncherApps::class)
class AppCommandLauncherShadow : org.robolectric.shadows.ShadowLauncherApps() {
    var detailsComponent: android.content.ComponentName? = null
    var detailsUser: android.os.UserHandle? = null
    var systemApp = false

    @org.robolectric.annotation.Implementation
    public override fun getActivityList(packageName: String?, user: android.os.UserHandle?): List<android.content.pm.LauncherActivityInfo> {
        if (packageName == null) return emptyList()
        val activity = org.robolectric.shadow.api.Shadow.newInstanceOf(android.content.pm.LauncherActivityInfo::class.java)
        org.robolectric.shadow.api.Shadow.extract<AppCommandActivityShadow>(activity).apply {
            component = android.content.ComponentName(packageName, "MainActivity")
            application = android.content.pm.ApplicationInfo().apply {
                this.packageName = packageName
                flags = if (systemApp) android.content.pm.ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
        return listOf(activity)
    }

    @org.robolectric.annotation.Implementation
    public override fun startAppDetailsActivity(component: android.content.ComponentName, user: android.os.UserHandle,
        bounds: Rect?, options: Bundle?) {
        detailsComponent = component
        detailsUser = user
    }
}

@org.robolectric.annotation.Implements(android.content.pm.LauncherActivityInfo::class)
class AppCommandActivityShadow {
    lateinit var component: android.content.ComponentName
    lateinit var application: android.content.pm.ApplicationInfo

    @org.robolectric.annotation.Implementation
    fun getComponentName() = component

    @org.robolectric.annotation.Implementation
    fun getApplicationInfo() = application

    @org.robolectric.annotation.Implementation
    fun getBadgedIcon(density: Int): android.graphics.drawable.Drawable = ColorDrawable(Color.WHITE)
}
