package com.idp.universalremote.presentation.apps

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.idp.universalremote.R
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentAppsBinding

class AppsFragment : Fragment(R.layout.fragment_apps) {

    private val binding by viewBinding(FragmentAppsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Static showcase grid of TV apps; click actions launch their TV-side counterparts.
    }
}
