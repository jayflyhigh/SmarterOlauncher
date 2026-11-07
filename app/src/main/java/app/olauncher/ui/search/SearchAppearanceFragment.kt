package app.olauncher.ui.search

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import app.olauncher.R
import app.olauncher.data.AppSearchAppearance
import app.olauncher.data.Prefs
import app.olauncher.data.SearchMode
import app.olauncher.data.Constants
import app.olauncher.databinding.FragmentSearchAppearanceBinding
import app.olauncher.databinding.ItemSearchHeaderBinding
import app.olauncher.data.SearchEngines
import app.olauncher.databinding.ItemSearchAppBinding
import app.olauncher.databinding.PopupSearchPageMenuBinding
import app.olauncher.databinding.ViewAppSearchColorPickerBinding
import app.olauncher.helper.getColorFromAttr
import app.olauncher.ui.BaseFragment
import java.util.Locale
import kotlin.math.roundToInt

open class SearchAppearanceFragment : BaseFragment() {
    private var _binding: FragmentSearchAppearanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private lateinit var appearance: AppSearchAppearance
    private lateinit var previewAdapter: PreviewAdapter
    private lateinit var tilePicker: AppSearchColorPicker
    private lateinit var backgroundPicker: AppSearchColorPicker
    private lateinit var popupPicker: AppSearchColorPicker
    private lateinit var menuTilePicker: AppSearchColorPicker
    private var columns = 1
    private var previewMode = SearchMode.APPS_ONLY
    private var updatingControls = false
    private var previewPopup: PopupWindow? = null
    private var previewPopupBinding: PopupSearchPageMenuBinding? = null
    private val previewBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            previewPopup?.dismiss()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        previewMode = arguments?.getString(Constants.Key.SEARCH_MODE)?.let(SearchMode::from) ?: SearchMode.APPS_ONLY
        columns = savedInstanceState?.getInt(EDITING_COLUMNS) ?: prefs.searchTileCount(previewMode)
        appearance = prefs.searchAppearance(previewMode, columns)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, previewBackCallback)
        previewAdapter = PreviewAdapter()
        binding.previewResults.apply {
            layoutManager = GridLayoutManager(requireContext(), columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (previewAdapter.getItemViewType(position) == 3) spanCount else 1
                }
            }
            adapter = previewAdapter
            itemAnimator = null
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                appearance.applyResultsPadding(view, columns)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.appSearchSettingsRoot) { root, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            root.updatePadding(top = safe.top, bottom = safe.bottom, left = safe.left, right = safe.right)
            insets
        }
        binding.appSearchSettingsRoot.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val available = root.height - root.paddingTop - root.paddingBottom
            val height = minOf((250 * resources.displayMetrics.density).roundToInt(), (available * 0.45f).roundToInt())
            if (height > 0 && binding.preview.layoutParams.height != height)
                binding.preview.updateLayoutParams { this.height = height }
        }
        ViewCompat.requestApplyInsets(view)
        tilePicker = AppSearchColorPicker(binding.tileColorPicker, binding.tileColor) {
            update(appearance.copy(tileColor = it))
        }
        backgroundPicker = AppSearchColorPicker(binding.backgroundColorPicker, binding.backgroundColor) {
            update(appearance.copy(backgroundColor = it))
        }
        popupPicker = AppSearchColorPicker(binding.popupColorPicker, binding.popupColor) {
            update(appearance.copy(popupColor = it))
        }
        menuTilePicker = AppSearchColorPicker(binding.menuTileColorPicker, binding.menuTileColor) {
            update(appearance.copy(menuTileColor = it))
        }
        binding.decreaseLayout.setOnClickListener { if (columns == 2) chooseLayout(1) }
        binding.increaseLayout.setOnClickListener { if (columns == 1) chooseLayout(2) }
        binding.tileColor.setOnClickListener { togglePicker(binding.tileColorPicker.root) }
        binding.backgroundColor.setOnClickListener { togglePicker(binding.backgroundColorPicker.root) }
        binding.popupColor.setOnClickListener {
            togglePicker(binding.popupColorPicker.root)
            if (binding.popupColorPicker.root.isVisible && previewPopup == null) togglePreviewMenu()
        }
        binding.menuTileColor.setOnClickListener {
            togglePicker(binding.menuTileColorPicker.root)
            if (binding.menuTileColorPicker.root.isVisible && previewPopup == null) togglePreviewMenu()
        }
        binding.applyColorToMenu.setOnCheckedChangeListener { _, checked ->
            if (!updatingControls) update(appearance.copy(applyTileColorToMenu = checked))
        }
        binding.transparency.onUserProgress { update(appearance.copy(backgroundTransparency = it)) }
        binding.spacing.onUserProgress { update(appearance.copy(spacingDp = it)) }
        binding.paddingLeft.onUserProgress { update(appearance.copy(paddingLeftDp = it)) }
        binding.paddingRight.onUserProgress { update(appearance.copy(paddingRightDp = it)) }
        binding.paddingTop.onUserProgress { update(appearance.copy(paddingTopDp = it)) }
        binding.paddingBottom.onUserProgress { update(appearance.copy(paddingBottomDp = it)) }
        binding.previewMenu.setOnClickListener { togglePreviewMenu() }
        render()
        updatingControls = true
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        render()
    }

    private fun togglePicker(picker: View) {
        val show = !picker.isVisible
        listOf(binding.tileColorPicker.root, binding.backgroundColorPicker.root,
            binding.popupColorPicker.root, binding.menuTileColorPicker.root).forEach {
            it.isVisible = it === picker && show
        }
    }

    private fun chooseLayout(count: Int) {
        columns = count
        appearance = prefs.searchAppearance(previewMode, columns)
        previewPopup?.dismiss()
        val manager = binding.previewResults.layoutManager as GridLayoutManager
        manager.spanCount = columns
        manager.scrollToPositionWithOffset(0, 0)
        render()
    }

    private fun update(value: AppSearchAppearance) {
        appearance = value
        prefs.setSearchAppearance(previewMode, columns, value)
        render()
    }

    private fun render() {
        updatingControls = true
        val context = requireContext()
        binding.appearanceTitle.text = getString(R.string.search_appearance) + " - " + getString(when (previewMode) {
            SearchMode.APPS_ONLY -> R.string.apps
            SearchMode.CONTACTS_ONLY -> R.string.contacts
            SearchMode.ENGINES_ONLY -> R.string.search_heading_engines
            SearchMode.ALL_SEARCH -> R.string.search_anything
        })
        binding.editingLayout.setText(if (columns == 1) R.string.one_tile_across else R.string.two_tiles_across)
        binding.decreaseLayout.isEnabled = columns == 2
        binding.increaseLayout.isEnabled = columns == 1
        binding.decreaseLayout.alpha = if (columns == 2) 1f else 0.45f
        binding.increaseLayout.alpha = if (columns == 1) 1f else 0.45f
        tilePicker.setColor(appearance.tileColor ?: Color.rgb(32, 32, 32))
        backgroundPicker.setColor(appearance.backgroundColor ?: Color.BLACK)
        popupPicker.setColor(appearance.popupColor ?: Color.rgb(32, 32, 32))
        menuTilePicker.setColor(appearance.menuTileColor ?: Color.rgb(40, 40, 40))
        binding.applyColorToMenu.isChecked = appearance.applyTileColorToMenu
        binding.menuTileColor.isVisible = !appearance.applyTileColorToMenu
        if (appearance.applyTileColorToMenu) binding.menuTileColorPicker.root.isVisible = false
        val background = appearance.background(Color.BLACK)
        val transparency = appearance.backgroundTransparency ?: (100 - Color.alpha(background) * 100 / 255f).roundToInt()
        binding.transparency.progress = transparency
        binding.transparencyValue.text = getString(R.string.app_search_transparency_value, transparency)
        binding.spacing.max = AppSearchAppearance.MAX_SPACING_DP
        binding.spacing.progress = appearance.spacingDp ?: 8
        binding.spacingValue.text = appearance.spacingDp?.let { getString(R.string.app_search_spacing_value, it) }
            ?: getString(R.string.app_search_default_spacing)
        listOf(
            Triple(binding.paddingLeft, binding.paddingLeftValue, appearance.paddingLeftDp),
            Triple(binding.paddingRight, binding.paddingRightValue, appearance.paddingRightDp),
            Triple(binding.paddingTop, binding.paddingTopValue, appearance.paddingTopDp),
            Triple(binding.paddingBottom, binding.paddingBottomValue, appearance.paddingBottomDp),
        ).forEachIndexed { index, (slider, label, value) ->
            slider.max = AppSearchAppearance.MAX_PADDING_DP
            slider.progress = value
            label.text = getString(listOf(R.string.app_search_padding_left, R.string.app_search_padding_right,
                R.string.app_search_padding_top, R.string.app_search_padding_bottom)[index], value)
        }
        updatingControls = false
        binding.preview.setBackgroundColor(background)
        val foreground = Color.WHITE
        binding.previewTitle.setTextColor(foreground)
        binding.previewMenu.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
        appearance.applyResultsPadding(binding.previewResults, columns)
        previewAdapter.notifyItemRangeChanged(0, previewAdapter.itemCount)
        binding.previewResults.requestLayout()
        previewPopupBinding?.let {
            appearance.applyMenu(it)
        }
    }

    private fun togglePreviewMenu() {
        previewPopup?.let {
            it.dismiss()
            return
        }
        val menu = PopupSearchPageMenuBinding.inflate(layoutInflater)
        menu.searchPageAppearance.isVisible = false
        val density = resources.displayMetrics.density
        val popup = PopupWindow(
            menu.root,
            minOf((256 * density).roundToInt(), binding.root.width),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        )
        appearance.applyMenu(menu)
        // The content owns its colored drawable so color changes invalidate the visible view.
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Keep this preview visible while touches and keyboard input reach the color controls.
        popup.isOutsideTouchable = false
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        popup.elevation = 8 * density
        menu.tileCount.setText(if (columns == 1) R.string.one_tile_across else R.string.two_tiles_across)
        menu.decreaseTileCount.isEnabled = columns == 2
        menu.increaseTileCount.isEnabled = columns == 1
        menu.decreaseTileCount.alpha = if (columns == 2) 1f else 0.45f
        menu.increaseTileCount.alpha = if (columns == 1) 1f else 0.45f
        // This is a color preview, not a second control for the actual search layout.
        menu.searchPageSettings.setOnClickListener { popup.dismiss() }
        menu.decreaseTileCount.setOnClickListener { chooseLayout(1) }
        menu.increaseTileCount.setOnClickListener { chooseLayout(2) }
        popup.setOnDismissListener {
            previewPopup = null
            previewPopupBinding = null
            previewBackCallback.isEnabled = false
        }
        previewPopup = popup
        previewPopupBinding = menu
        previewBackCallback.isEnabled = true
        popup.showAsDropDown(binding.previewMenu, 0, 0, Gravity.END)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(EDITING_COLUMNS, columns)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        previewPopup?.dismiss()
        binding.previewResults.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private inner class PreviewAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val context = requireContext()
        private val packageManager = context.packageManager
        private val settings = Intent(Settings.ACTION_SETTINGS).resolveActivityInfo(packageManager, 0)
        private val samples = listOf(
            getString(R.string.app_name) to context.applicationInfo.loadIcon(packageManager),
            (settings?.loadLabel(packageManager)?.toString() ?: getString(R.string.app_search_sample_settings)) to
                (settings?.loadIcon(packageManager)
                    ?: requireNotNull(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_preferences))),
        )

        private val engines = SearchEngines.get(context).take(2)

        override fun getItemCount() = if (previewMode == SearchMode.ALL_SEARCH) 9 else 2

        override fun getItemViewType(position: Int): Int = when (previewMode) {
            SearchMode.APPS_ONLY -> 0
            SearchMode.CONTACTS_ONLY -> 1
            SearchMode.ENGINES_ONLY -> 2
            SearchMode.ALL_SEARCH -> if (position % 3 == 0) 3 else position / 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            3 -> HeaderPreviewHolder(ItemSearchHeaderBinding.inflate(layoutInflater, parent, false))
            else -> PreviewHolder(ItemSearchAppBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val index = if (previewMode == SearchMode.ALL_SEARCH) (position % 3 - 1).coerceAtLeast(0) else position
            when (holder) {
                is PreviewHolder -> {
                    when (getItemViewType(position)) {
                        1 -> holder.bind(
                            getString(if (index == 0) R.string.preview_contact_one else R.string.preview_contact_two),
                            requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_person)),
                            R.string.search_type_contact,
                        )
                        2 -> holder.bind(
                            engines.getOrNull(index)?.name ?: getString(R.string.search_engines),
                            requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_search_web)),
                            R.string.search_type_engine,
                        )
                        else -> {
                            val (label, icon) = samples[index]
                            holder.bind(label, icon, R.string.search_type_app)
                        }
                    }
                }
                is HeaderPreviewHolder -> with(holder.row) {
                    headerTitle.setText(listOf(R.string.apps, R.string.contacts, R.string.search)[position / 3])
                    headerTitle.gravity = prefs.appLabelAlignment
                }
            }
        }
    }

    private class HeaderPreviewHolder(val row: ItemSearchHeaderBinding) : RecyclerView.ViewHolder(row.root)

    private inner class PreviewHolder(private val row: ItemSearchAppBinding) : RecyclerView.ViewHolder(row.root) {
        private val style = AppSearchTileStyle(row)

        fun bind(label: String, icon: Drawable, type: Int) {
            row.itemTitle.text = label
            row.itemType.setText(type)
            row.itemIcon.setImageDrawable(icon)
            style.apply(appearance, columns, prefs.appLabelAlignment)
        }
    }

    private companion object {
        const val EDITING_COLUMNS = "editing_app_search_columns"
    }
}

private fun SeekBar.onUserProgress(onChange: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    })
}

internal class AppSearchColorPicker(
    private val binding: ViewAppSearchColorPickerBinding,
    private val button: TextView?,
    private val onChange: (Int) -> Unit,
) {
    private var color: Int? = null
    private var updating = false
    private val hsv = floatArrayOf(0f, 0f, 1f)

    init {
        listOf(binding.hex, binding.wheel, binding.brightness).forEach { it.isSaveEnabled = false }
        binding.wheel.onColorChanged = {
            Color.colorToHSV(it, hsv)
            hsv[2] = binding.brightness.progress / 100f
            val value = Color.HSVToColor(hsv)
            setColor(value, keepHue = true)
            onChange(value)
        }
        binding.brightness.onUserProgress {
            hsv[2] = it / 100f
            val value = Color.HSVToColor(hsv)
            setColor(value, keepHue = true)
            onChange(value)
        }
        binding.hex.doAfterTextChanged { text ->
            if (!updating) {
                val hex = text.toString().removePrefix("#")
                if (hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                    val value = hex.toInt(16) or Color.BLACK
                    color = value
                    updateControls(value)
                    binding.hex.error = null
                    onChange(value)
                } else {
                    binding.hex.error = binding.root.context.getString(R.string.app_search_invalid_color)
                }
            }
        }
    }

    fun setColor(value: Int, keepHue: Boolean = false) {
        val opaque = value or Color.BLACK
        if (color == opaque && !keepHue) return
        color = opaque
        updating = true
        binding.hex.setText(String.format(Locale.ROOT, "#%06X", opaque and 0xffffff))
        binding.hex.error = null
        updateControls(opaque, keepHue)
        updating = false
    }

    private fun updateControls(value: Int, keepHue: Boolean = false) {
        if (!keepHue) Color.colorToHSV(value, hsv)
        val brightness = (hsv[2] * 100).roundToInt()
        binding.brightness.progress = brightness
        binding.brightnessValue.text = binding.root.context.getString(R.string.app_search_brightness_value, brightness)
        binding.wheel.setColor(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        val button = button ?: return
        val size = (24 * button.resources.displayMetrics.density).roundToInt()
        val swatch = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(value)
            setStroke((button.resources.displayMetrics.density).roundToInt().coerceAtLeast(1), Color.GRAY)
            setBounds(0, 0, size, size)
        }
        button.setCompoundDrawablesRelative(null, null, swatch, null)
    }
}
