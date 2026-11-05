package dev.ownednotes.app

import androidx.core.content.FileProvider

/** F107: its own provider class so the manifest can hold it beside P083's scan provider. */
class AttachmentFileProvider : FileProvider()
