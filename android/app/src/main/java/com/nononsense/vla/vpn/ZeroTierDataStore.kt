package com.nononsense.vla.vpn

import android.content.Context
import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import java.io.File
import java.io.FileNotFoundException

class ZeroTierDataStore(context: Context) : DataStoreGetListener, DataStorePutListener {
    private val root = File(context.filesDir, "zerotier").apply { mkdirs() }

    override fun onDataStoreGet(name: String, outBuffer: ByteArray): Long = try {
        val data = fileFor(name).readBytes()
        data.copyInto(outBuffer, endIndex = minOf(data.size, outBuffer.size))
        data.size.toLong()
    } catch (_: FileNotFoundException) {
        -1
    } catch (_: Exception) {
        -2
    }

    override fun onDataStorePut(name: String, buffer: ByteArray, secure: Boolean): Int = try {
        val target = fileFor(name)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(buffer)
        if (secure) {
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
        }
        if (!temporary.renameTo(target)) {
            target.writeBytes(buffer)
            temporary.delete()
        }
        0
    } catch (_: Exception) {
        -1
    }

    override fun onDelete(name: String): Int = try {
        val target = fileFor(name)
        if (!target.exists() || target.delete()) 0 else -1
    } catch (_: Exception) {
        -1
    }

    private fun fileFor(name: String): File {
        require(!name.contains("..") && !name.contains('\\'))
        val target = File(root, name).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator))
        return target
    }
}
