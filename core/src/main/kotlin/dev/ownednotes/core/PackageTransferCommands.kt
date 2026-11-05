package dev.ownednotes.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Local PC tooling. Neither command enables or changes the shared-folder protocol. */
internal object PackageTransferCommands {
    fun run(args: Array<String>, attachments: File) {
        require(args.size == 4) { "Usage: transfer-stage SOURCE.inknote BLOBS NEW_MANIFEST.json | transfer-hydrate MANIFEST.json BLOBS NEW_OUTPUT.inknote" }
        val input = File(args[1]); val blobs = File(args[2]); val output = File(args[3]).absoluteFile
        require(!Files.exists(output.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Output must be a new file; existing files are preserved" }
        require(output.parentFile.isDirectory && input.canonicalFile != output.canonicalFile) { "Invalid transfer output" }
        when (args[0]) {
            "transfer-stage" -> {
                NotebookPackage.read(input, attachments)
                require(blobs.isDirectory || blobs.mkdirs()) { "Cannot create blob directory" }
                val staged = PackageTransfer.stage(input, blobs)
                val temp = File.createTempFile("manifest-", ".pending", output.parentFile)
                try {
                    temp.writeBytes(PackageTransfer.encode(staged.manifest))
                    Files.move(temp.toPath(), output.toPath(), ATOMIC_MOVE)
                } finally { temp.delete() }
                println("{\"packageBytes\":${staged.manifest.size},\"newChunkBytes\":${staged.newBytes},\"sha256\":\"${staged.manifest.sha256}\"}")
            }
            "transfer-hydrate" -> {
                require(input.length() <= PackageTransfer.MAX_MANIFEST_BYTES) { "Transfer manifest too large" }
                val manifest = PackageTransfer.decode(input.readBytes())
                require(blobs.isDirectory && !Files.isSymbolicLink(blobs.toPath())) { "Owned blob directory required" }
                val candidate = File.createTempFile("native-", ".pending", output.parentFile)
                try {
                    PackageTransfer.hydrate(manifest, candidate, { part ->
                        val blob = File(blobs, part.sha256)
                        require(!Files.isSymbolicLink(blob.toPath())) { "Linked blob refused" }
                        blob.inputStream()
                    })
                    NotebookPackage.read(candidate, attachments)
                    Files.move(candidate.toPath(), output.toPath(), ATOMIC_MOVE)
                } finally { candidate.delete() }
                println("{\"packageBytes\":${manifest.size},\"sha256\":\"${manifest.sha256}\"}")
            }
            else -> error("Unknown transfer command")
        }
    }
}
