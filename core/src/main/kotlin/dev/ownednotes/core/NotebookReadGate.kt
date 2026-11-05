package dev.ownednotes.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Short per-notebook local commit/read gate. Never hold it across a provider/network call. */
object NotebookReadGate {
    private val locks=ConcurrentHashMap<String,Any>()
    fun <T> access(directory:File,id:String,readOrCommit:()->T):T {
        val key=directory.canonicalPath+"/"+id
        return synchronized(locks.computeIfAbsent(key){Any()}){readOrCommit()}
    }
}
