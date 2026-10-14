package dev.huenotes

data class Note(
    val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val checklist: Boolean = false,
    val color: Int = COLORS[0],
    val archived: Boolean = false,
    val reminderAt: Long = 0,
    val modifiedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /** 0 = not in the trash, otherwise when it was moved there. */
    val deletedAt: Long = 0,
) {
    val isEmpty get() = title.isBlank() && body.isBlank()

    companion object {
        val COLORS = intArrayOf(0xFFFFF59D.toInt(), 0xFFFFCC80.toInt(), 0xFFEF9A9A.toInt(),
            0xFFA5D6A7.toInt(), 0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(), 0xFF80CBC4.toInt(), 0xFFF48FB1.toInt())
    }
}
