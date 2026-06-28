package com.idp.universalremote.presentation.cast

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MediaPagerAdapter(
    fragment: Fragment,
    private val mediaType: String
) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = 2
    override fun createFragment(position: Int): Fragment {
        val frag = MediaGridFragment()
        frag.arguments = androidx.core.os.bundleOf("type" to mediaType, "tab" to position)
        return frag
    }
}
