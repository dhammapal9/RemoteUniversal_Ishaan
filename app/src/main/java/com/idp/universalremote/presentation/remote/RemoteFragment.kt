package com.idp.universalremote.presentation.remote

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.idp.universalremote.R
import com.idp.universalremote.core.common.vibrate
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentRemoteBinding
import com.idp.universalremote.domain.model.RemoteKey
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs

@AndroidEntryPoint
class RemoteFragment : Fragment(R.layout.fragment_remote) {

    private val binding by viewBinding(FragmentRemoteBinding::bind)
    private val viewModel: RemoteViewModel by viewModels()

    private enum class Mode(val index: Int) { DPAD(0), TOUCHPAD(1), NUMERIC(2), KEYBOARD(3) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupModeSwitcher()
        setupDpad()
        setupTouchpad()
        setupNumeric()
        setupKeyboard()
        setupBottomControls()
        setupAppShortcuts()
        selectMode(Mode.DPAD)
    }

    private fun setupHeader() {
        binding.btnHome.setOnClickListener { sendKey(RemoteKey.HOME, it) }
        binding.btnPower.setOnClickListener { sendKey(RemoteKey.POWER, it) }
    }

    private fun setupModeSwitcher() {
        binding.modeDpad.setOnClickListener { selectMode(Mode.DPAD) }
        binding.modeTouchpad.setOnClickListener { selectMode(Mode.TOUCHPAD) }
        binding.modeNumeric.setOnClickListener { selectMode(Mode.NUMERIC) }
        binding.modeKeyboard.setOnClickListener { selectMode(Mode.KEYBOARD) }
    }

    private fun selectMode(mode: Mode) {
        binding.modeFlipper.displayedChild = mode.index
        binding.modeDpad.isSelected = mode == Mode.DPAD
        binding.modeTouchpad.isSelected = mode == Mode.TOUCHPAD
        binding.modeNumeric.isSelected = mode == Mode.NUMERIC
        binding.modeKeyboard.isSelected = mode == Mode.KEYBOARD
        if (mode == Mode.KEYBOARD) {
            binding.kbInput.requestFocus()
        } else {
            binding.kbInput.clearFocus()
        }
    }

    private fun setupDpad() {
        binding.dpadUp.setOnClickListener { sendKey(RemoteKey.UP, it) }
        binding.dpadDown.setOnClickListener { sendKey(RemoteKey.DOWN, it) }
        binding.dpadLeft.setOnClickListener { sendKey(RemoteKey.LEFT, it) }
        binding.dpadRight.setOnClickListener { sendKey(RemoteKey.RIGHT, it) }
        binding.dpadOk.setOnClickListener { sendKey(RemoteKey.OK, it) }
    }

    private fun setupTouchpad() {
        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                sendKey(RemoteKey.OK, binding.touchpadSurface)
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < SWIPE_THRESHOLD && abs(dy) < SWIPE_THRESHOLD) return false
                val key = if (abs(dx) > abs(dy)) {
                    if (dx > 0) RemoteKey.RIGHT else RemoteKey.LEFT
                } else {
                    if (dy > 0) RemoteKey.DOWN else RemoteKey.UP
                }
                sendKey(key, binding.touchpadSurface)
                return true
            }
        })
        binding.touchpadSurface.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun setupNumeric() {
        val numKeys = listOf(
            binding.num0 to RemoteKey.NUM_0,
            binding.num1 to RemoteKey.NUM_1,
            binding.num2 to RemoteKey.NUM_2,
            binding.num3 to RemoteKey.NUM_3,
            binding.num4 to RemoteKey.NUM_4,
            binding.num5 to RemoteKey.NUM_5,
            binding.num6 to RemoteKey.NUM_6,
            binding.num7 to RemoteKey.NUM_7,
            binding.num8 to RemoteKey.NUM_8,
            binding.num9 to RemoteKey.NUM_9
        )
        numKeys.forEach { (view, key) ->
            view.setOnClickListener { sendKey(key, it) }
        }
        binding.numExit.setOnClickListener { sendKey(RemoteKey.EXIT, it) }
        binding.numBackspace.setOnClickListener { sendKey(RemoteKey.BACKSPACE, it) }
    }

    private fun setupKeyboard() {
        binding.kbInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.kbInput.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    viewModel.sendText(text)
                    binding.kbInput.text?.clear()
                }
                true
            } else false
        }
    }

    private fun setupBottomControls() {
        binding.btnBack.setOnClickListener { sendKey(RemoteKey.BACK, it) }
        binding.btnReplay.setOnClickListener { sendKey(RemoteKey.INSTANT_REPLAY, it) }
        binding.btnOptions.setOnClickListener { sendKey(RemoteKey.OPTIONS, it) }
        binding.btnRewind.setOnClickListener { sendKey(RemoteKey.REWIND, it) }
        binding.btnPlayPause.setOnClickListener { sendKey(RemoteKey.PLAY_PAUSE, it) }
        binding.btnForward.setOnClickListener { sendKey(RemoteKey.FORWARD, it) }
        binding.btnInput.setOnClickListener { sendKey(RemoteKey.SOURCE, it) }
        binding.btnVoice.setOnClickListener { sendKey(RemoteKey.VOICE, it) }
        binding.btnMute.setOnClickListener { sendKey(RemoteKey.MUTE, it) }
        binding.btnVolUp.setOnClickListener { sendKey(RemoteKey.VOL_UP, it) }
        binding.btnVolDown.setOnClickListener { sendKey(RemoteKey.VOL_DOWN, it) }
    }

    private fun setupAppShortcuts() {
        binding.appNetflix.setOnClickListener { sendKey(RemoteKey.APP_NETFLIX, it) }
        binding.appYoutube.setOnClickListener { sendKey(RemoteKey.APP_YOUTUBE, it) }
        binding.appAppleTv.setOnClickListener { sendKey(RemoteKey.APP_APPLE_TV, it) }
    }

    private fun sendKey(key: RemoteKey, anchor: View) {
        anchor.vibrate()
        viewModel.send(key)
    }

    companion object {
        private const val SWIPE_THRESHOLD = 40f
    }
}
