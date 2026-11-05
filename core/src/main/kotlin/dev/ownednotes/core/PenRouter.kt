package dev.ownednotes.core

enum class PointerTool { PEN, ERASER, FINGER, MOUSE, UNKNOWN }
enum class PointerAction { DOWN, MOVE, UP, CANCEL }
data class Pointer(val id: Int, val tool: PointerTool)
data class PointerPacket(val action: PointerAction, val actionId: Int, val pointers: List<Pointer>, val canceled: Boolean = false)
enum class InkAction { START, APPEND, FINISH, CANCEL }
data class RoutedInput(val action: InkAction, val pointerId: Int, val tool: PointerTool)

/** Pen-first routing; one finger can ink only with explicit host opt-in. Mouse never inks. */
class PenRouter {
    private val active = linkedMapOf<Int, PointerTool>()
    val hasActivePen get() = active.isNotEmpty()
    fun route(packet: PointerPacket, allowFinger: Boolean = false): List<RoutedInput> {
        val interrupted = active.filter { (_, tool) -> tool == PointerTool.FINGER &&
            (!allowFinger || packet.pointers.size != 1 || packet.pointers.any { it.tool == PointerTool.PEN || it.tool == PointerTool.ERASER }) }
            .map { (id, tool) -> RoutedInput(InkAction.CANCEL, id, tool) }
        interrupted.forEach { active.remove(it.pointerId) }
        val result = when (packet.action) {
            PointerAction.DOWN -> {
                val pointer = packet.pointers.firstOrNull { it.id == packet.actionId } ?: return interrupted
                val finger = pointer.tool == PointerTool.FINGER && allowFinger && packet.pointers.size == 1 && active.isEmpty()
                if (pointer.tool != PointerTool.PEN && pointer.tool != PointerTool.ERASER && !finger) return interrupted
                if (active.containsKey(pointer.id)) return interrupted
                active[pointer.id] = pointer.tool
                listOf(RoutedInput(InkAction.START, pointer.id, pointer.tool))
            }
            PointerAction.MOVE -> packet.pointers.mapNotNull { pointer ->
                active[pointer.id]?.let { RoutedInput(InkAction.APPEND, pointer.id, it) }
            }
            PointerAction.UP -> {
                val tool = active.remove(packet.actionId) ?: return interrupted
                listOf(RoutedInput(if (packet.canceled) InkAction.CANCEL else InkAction.FINISH, packet.actionId, tool))
            }
            PointerAction.CANCEL -> active.map { RoutedInput(InkAction.CANCEL, it.key, it.value) }.also { active.clear() }
        }
        return interrupted + result
    }
}
