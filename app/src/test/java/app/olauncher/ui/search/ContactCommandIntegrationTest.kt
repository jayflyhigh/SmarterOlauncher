package app.olauncher.ui.search

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.EditText
import android.view.inputmethod.BaseInputConnection
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
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
import org.robolectric.android.controller.ActivityController
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
@LooperMode(LooperMode.Mode.PAUSED)
class ContactCommandIntegrationTest {
    private lateinit var searchController: ActivityController<ContactCommandTestActivity>
    private fun withSearch(
        columns: Int = 1,
        mode: SearchMode = SearchMode.CONTACTS_ONLY,
        automatic: Boolean = true,
        action: (ContactCommandTestActivity, UniversalSearchFragment, CommandContactsProvider) -> Unit,
    ) {
        val provider = CommandContactsProvider().apply { install() }
        val controller = Robolectric.buildActivity(ContactCommandTestActivity::class.java).setup()
        searchController = controller
        try {
            val activity = controller.get()
            Prefs(activity).apply {
                setSearchTileCount(mode, columns)
                setContactCommandSettings(ContactCommandSettings(automatic = automatic))
            }
            val fragment = UniversalSearchFragment().apply {
                arguments = Bundle().apply { putString(Constants.Key.SEARCH_MODE, mode.name) }
            }
            activity.supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment).commitNow()
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]
            awaitCommand { vm.contactsLoading.value == false && vm.contacts.value?.size == 2 }
            awaitCommand { results(fragment).currentList.count { it is SearchItem.ContactItem } == 2 }
            action(activity, fragment, provider)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun results(fragment: UniversalSearchFragment) =
        fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView).adapter as SearchResultsAdapter

    private fun contactResults(fragment: UniversalSearchFragment) =
        results(fragment).currentList.filterIsInstance<SearchItem.ContactItem>()

    private fun query(fragment: UniversalSearchFragment, text: String) {
        fragment.requireView().findViewById<SearchView>(R.id.search).setQuery(text, false)
    }

    private fun layout(fragment: UniversalSearchFragment) {
        fragment.requireView().apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1080, 1920)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `all commands automatically launch correct intents once with either case`() {
        for (command in ContactCommand.entries) {
            for (letter in listOf(command.letter, command.letter.uppercaseChar())) {
                withSearch { activity, fragment, _ ->
                    query(fragment, "$letter john")
                    awaitCommand { contactResults(fragment).size == 1 }
                    assertNull(shadowOf(activity).peekNextStartedActivity())
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(249))
                    assertNull(shadowOf(activity).peekNextStartedActivity())
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
                    awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
                    val intent = shadowOf(activity).nextStartedActivity
                    when (command) {
                        ContactCommand.PHONE -> {
                            assertEquals(Intent.ACTION_DIAL, intent.action)
                            assertEquals("tel", intent.data?.scheme)
                            assertEquals("+15551000", intent.data?.schemeSpecificPart)
                        }
                        ContactCommand.MESSAGE -> {
                            assertEquals(Intent.ACTION_SENDTO, intent.action)
                            assertEquals("smsto", intent.data?.scheme)
                            assertEquals("+15551000", intent.data?.schemeSpecificPart)
                        }
                        ContactCommand.WHATSAPP -> {
                            assertEquals("com.whatsapp", intent.`package`)
                            assertEquals("https://api.whatsapp.com/send?phone=15551000", intent.dataString)
                        }
                        ContactCommand.CONTACT -> {
                            assertEquals(Intent.ACTION_VIEW, intent.action)
                            assertEquals("content://com.android.contacts/contacts/1", intent.dataString)
                        }
                    }
                    ViewModelProvider(activity)[MainViewModel::class.java].contacts.value =
                        ViewModelProvider(activity)[MainViewModel::class.java].contacts.value
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                    assertNull(shadowOf(activity).peekNextStartedActivity())
                }
            }
        }
    }

    @Test
    fun `manual taps execute commands in either layout and cancel the timer`() {
        for (columns in 1..2) {
            for (command in ContactCommand.entries) {
                withSearch(columns) { activity, fragment, _ ->
                    query(fragment, "${command.letter} john")
                    awaitCommand { contactResults(fragment).size == 1 }
                    layout(fragment)
                    val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
                    val tile = recycler.getChildAt(0)
                    tile.performClick()
                    awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
                    shadowOf(activity).nextStartedActivity
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                    assertNull(shadowOf(activity).peekNextStartedActivity())
                }

            }
        }
    }

    @Test
    fun `manual selection from multiple matches chooses the tapped contact in both layouts`() {
        for (columns in 1..2) {
            withSearch(columns) { activity, fragment, _ ->
                query(fragment, "p j")
                awaitCommand { contactResults(fragment).size == 2 }
                layout(fragment)
                val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
                val tile = recycler.getChildAt(0)
                val expected = (results(fragment).currentList[
                    recycler.getChildAdapterPosition(tile)
                ] as SearchItem.ContactItem).contact.number
                tile.performClick()
                awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
                assertEquals(expected, shadowOf(activity).nextStartedActivity.data?.schemeSpecificPart)
            }
        }
    }

    @Test
    fun `long press still opens details during command mode and inline shortcuts keep their actions`() {
        withSearch { activity, fragment, _ ->
            query(fragment, "m john")
            awaitCommand { contactResults(fragment).size == 1 }
            val contact = contactResults(fragment).single().contact
            fragment.onContactLongClick(contact)
            assertEquals(Intent.ACTION_VIEW, shadowOf(activity).nextStartedActivity.action)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            query(fragment, "w john")
            awaitCommand { contactResults(fragment).size == 1 }
            fragment.onContactText(contact)
            assertEquals(Intent.ACTION_SENDTO, shadowOf(activity).nextStartedActivity.action)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `delay minimum and hidden indicator settings are honored`() {
        withSearch { activity, fragment, _ ->
            Prefs(activity).setContactCommandSettings(ContactCommandSettings(
                delayMillis = 750, minimumCharacters = 4, showIndicator = false
            ))
            query(fragment, "p joh")
            awaitCommand { contactResults(fragment).size == 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            query(fragment, "p nobody")
            awaitCommand { contactResults(fragment).isEmpty() }
            query(fragment, "p john")
            awaitCommand { contactResults(fragment).size == 1 }
            assertEquals("Contacts", fragment.requireView().findViewById<TextView>(R.id.modeTitle).text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(749))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
        }
    }

    @Test
    fun `disabled auto execution still supports uniquely submitted command`() {
        withSearch(automatic = false) { activity, fragment, _ ->
            query(fragment, "m john")
            awaitCommand { contactResults(fragment).size == 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            fragment.requireView().findViewById<SearchView>(R.id.search).setQuery("m john", true)
            awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
            assertEquals(Intent.ACTION_SENDTO, shadowOf(activity).nextStartedActivity.action)
        }
    }

    @Test
    fun `Enter on command without name opens corresponding app and typing alone does not`() {
        for (command in ContactCommand.entries) {
            for (text in listOf(command.letter.toString(), "${command.letter.uppercaseChar()}   ")) {
                withSearch { activity, fragment, _ ->
                    if (command == ContactCommand.WHATSAPP) {
                        val launch = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.whatsapp")
                        shadowOf(activity.packageManager).addResolveInfoForIntent(launch, ResolveInfo().apply {
                            activityInfo = ActivityInfo().apply {
                                packageName = "com.whatsapp"
                                name = "com.whatsapp.Main"
                                enabled = true
                                exported = true
                                applicationInfo = ApplicationInfo().apply {
                                    packageName = "com.whatsapp"
                                    enabled = true
                                }
                            }
                        })
                    }
                    query(fragment, text)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                    assertNull(shadowOf(activity).peekNextStartedActivity())
                    fragment.requireView().findViewById<SearchView>(R.id.search).setQuery(text, true)
                    awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
                    val intent = shadowOf(activity).nextStartedActivity
                    when (command) {
                        ContactCommand.PHONE -> assertEquals(Intent.ACTION_DIAL, intent.action)
                        ContactCommand.MESSAGE -> assertTrue(intent.categories.contains(Intent.CATEGORY_APP_MESSAGING))
                        ContactCommand.WHATSAPP -> assertEquals("com.whatsapp", intent.component?.packageName)
                        ContactCommand.CONTACT -> assertTrue(intent.categories.contains(Intent.CATEGORY_APP_CONTACTS))
                    }
                    assertNull(intent.data)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                    assertNull(shadowOf(activity).peekNextStartedActivity())
                }
            }
        }
    }

    @Test
    fun `rapid query changes ambiguity and loading cannot launch stale results`() {
        withSearch { activity, fragment, _ ->
            query(fragment, "p john")
            awaitCommand { contactResults(fragment).size == 1 }
            query(fragment, "p j")
            awaitCommand { contactResults(fragment).size == 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            query(fragment, "p jane")
            awaitCommand { contactResults(fragment).size == 1 }
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]
            vm.contactsLoading.value = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            vm.contactsLoading.value = false
            query(fragment, "ordinary search")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `composing text never automatically executes`() {
        withSearch { activity, fragment, _ ->
            query(fragment, "p john")
            awaitCommand { contactResults(fragment).size == 1 }
            val editor = fragment.requireView().findViewById<EditText>(R.id.search_src_text)
            BaseInputConnection.setComposingSpans(editor.text)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `returning and recreating never repeats an executed command`() {
        withSearch { activity, fragment, _ ->
            query(fragment, "p john")
            awaitCommand { contactResults(fragment).size == 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            awaitCommand { shadowOf(activity).peekNextStartedActivity() != null }
            shadowOf(activity).nextStartedActivity
            searchController.pause().stop().start().resume().visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            searchController.recreate()
            val recreated = searchController.get()
            val restored = recreated.supportFragmentManager.findFragmentById(android.R.id.content)!!
            assertEquals(
                "p john", restored.requireView().findViewById<SearchView>(R.id.search).query.toString()
            )
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(recreated).peekNextStartedActivity())
        }
    }

    @Test
    fun `leaving before stabilization cancels pending automatic execution`() {
        withSearch { activity, fragment, _ ->
            query(fragment, "p john")
            awaitCommand { contactResults(fragment).size == 1 }
            searchController.pause().stop()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            searchController.start().resume().visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
        }
    }

    @Test
    fun `command prefixes have no special meaning in Search Everything`() {
        withSearch(mode = SearchMode.ALL_SEARCH) { activity, fragment, _ ->
            query(fragment, "p john")
            awaitCommand { results(fragment).currentList.none { it is SearchItem.ContactItem } }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).peekNextStartedActivity())
            assertEquals("Search Anything", fragment.requireView().findViewById<TextView>(R.id.modeTitle).text.toString())
            assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.commandIndicator).visibility)
        }
    }

    @Test
    fun `indicator reacts immediately and ordinary contact search stays unchanged`() {
        withSearch(automatic = false) { _, fragment, provider ->
            val count = provider.queryCount
            query(fragment, "W ")
            assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.commandIndicator).visibility)
            assertEquals("Contacts", fragment.requireView().findViewById<TextView>(R.id.modeTitle).text.toString())
            query(fragment, "john")
            awaitCommand { contactResults(fragment).size == 1 }
            assertEquals("Contacts", fragment.requireView().findViewById<TextView>(R.id.modeTitle).text.toString())
            assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.commandIndicator).visibility)
            assertEquals(count, provider.queryCount)
            fragment.onContactClick(contactResults(fragment).single().contact)
        }
    }
}
