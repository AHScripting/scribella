package dev.ownednotes.app

import android.content.Context

/**
 * P565: tools made for one owner's own workflow, which would only confuse anyone else: the study copy for a PC, the
 * handwriting-only export, automatic readable copies in Downloads, the smaller-uploads test and the Match Standard
 * corrections for older imported pages. Off by default, so the public source and every release build leave them out.
 * Only a private debug resource turns them on; the code behind them stays, unreachable.
 */
internal val Context.personalTools: Boolean get() = resources.getBoolean(R.bool.personal_tools)
