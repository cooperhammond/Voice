package voice.app.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.core.DataStore
import androidx.lifecycle.lifecycleScope
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import voice.app.AppController
import voice.app.databinding.ActivityBookBinding
import voice.app.features.audio.PlaybackSpeedDialogController
import voice.app.features.bookOverview.EditCoverDialogController
import voice.app.features.bookPlaying.selectchapter.SelectChapterDialog
import voice.app.features.bookmarks.BookmarkController
import voice.app.features.imagepicker.CoverFromInternetController
import voice.app.injection.appComponent
import voice.app.misc.conductor.asTransaction
import voice.common.BookId
import voice.common.navigation.Navigator
import voice.common.navigation.Screen
import voice.common.pref.CurrentBook
import voice.playback.PlayerController
import voice.playback.session.search.BookSearchHandler
import voice.playback.session.search.BookSearchParser
import voice.playbackScreen.BookPlayController
import javax.inject.Inject

class MainActivity : AppCompatActivity() {

  @field:[Inject CurrentBook]
  lateinit var currentBook: DataStore<BookId?>

  @Inject
  lateinit var bookSearchParser: BookSearchParser

  @Inject
  lateinit var bookSearchHandler: BookSearchHandler

  @Inject
  lateinit var playerController: PlayerController

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var galleryPicker: GalleryPicker

  private lateinit var router: Router

  override fun onCreate(savedInstanceState: Bundle?) {
    appComponent.inject(this)
    super.onCreate(savedInstanceState)
    val binding = ActivityBookBinding.inflate(layoutInflater)
    setContentView(binding.root)

    router = Conductor.attachRouter(this, binding.root, savedInstanceState)
    if (!router.hasRootController()) {
      setupRouter()
    }

    router.addChangeListener(
      object : ControllerChangeHandler.ControllerChangeListener {
        override fun onChangeStarted(
          to: Controller?,
          from: Controller?,
          isPush: Boolean,
          container: ViewGroup,
          handler: ControllerChangeHandler
        ) {
          from?.setOptionsMenuHidden(true)
        }

        override fun onChangeCompleted(
          to: Controller?,
          from: Controller?,
          isPush: Boolean,
          container: ViewGroup,
          handler: ControllerChangeHandler
        ) {
          from?.setOptionsMenuHidden(false)
        }
      }
    )

    lifecycleScope.launch {
      navigator.conductorCommands.collect { screen ->
        when (screen) {
          Screen.PlaybackSpeedDialog -> {
            PlaybackSpeedDialogController().showDialog(router)
          }
          is Screen.BookmarkDialog -> {
            router.pushController(BookmarkController(screen.bookId).asTransaction())
          }
          is Screen.SelectChapterDialog -> {
            SelectChapterDialog(screen.bookId).showDialog(router)
          }
          is Screen.CoverFromFiles -> {
            galleryPicker.pick(screen.bookId, this@MainActivity)
          }
          is Screen.CoverFromInternet -> {
            router.pushController(CoverFromInternetController(screen.bookId).asTransaction())
          }
          is Screen.Playback -> {
            lifecycleScope.launch {
              currentBook.updateData { screen.bookId }
              router.pushController(BookPlayController(screen.bookId).asTransaction())
            }
          }
        }
      }
    }

    setupFromIntent(intent)
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setupFromIntent(intent)
  }

  private fun setupFromIntent(intent: Intent?) {
    bookSearchParser.parse(intent)?.let {
      runBlocking {
        bookSearchHandler.handle(it)
      }
    }
  }

  private fun setupRouter() {
    // if we should enter a book set the backstack and return early
    intent.getStringExtra(NI_GO_TO_BOOK)
      ?.let {
        val bookId = BookId(it)
        val bookShelf = RouterTransaction.with(AppController())
        val bookPlay = BookPlayController(bookId).asTransaction()
        router.setBackstack(listOf(bookShelf, bookPlay), null)
        return
      }

    // if we should play the current book, set the backstack and return early
    if (intent?.action == "playCurrent") {
      runBlocking { currentBook.data.first() }?.let { bookId ->
        val bookShelf = RouterTransaction.with(AppController())
        val bookPlay = BookPlayController(bookId).asTransaction()
        router.setBackstack(listOf(bookShelf, bookPlay), null)
        playerController.play()
        return
      }
    }

    val rootTransaction = RouterTransaction.with(AppController())
    router.setRoot(rootTransaction)
  }


  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val arguments = galleryPicker.parse(requestCode, resultCode, data)
    if (arguments != null) {
      EditCoverDialogController(arguments).showDialog(router)
    }
  }

  override fun onBackPressed() {
    if (router.backstackSize == 1) {
      super.onBackPressed()
    } else router.handleBack()
  }

  companion object {
    private const val NI_GO_TO_BOOK = "niGotoBook"

    /** Returns an intent that lets you go directly to the playback screen for a certain book **/
    fun goToBookIntent(context: Context, bookId: BookId) = Intent(context, MainActivity::class.java).apply {
      putExtra(NI_GO_TO_BOOK, bookId.value)
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    }
  }
}
