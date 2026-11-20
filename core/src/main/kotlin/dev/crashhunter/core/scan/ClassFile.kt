package dev.crashhunter.core.scan

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.EOFException

@Serializable
data class MemberRef(val owner: String, val name: String, val desc: String, val field: Boolean = false)

@Serializable
data class MethodInfo(val name: String, val desc: String, val access: Int)

@Serializable
data class ClassInfo(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val methods: List<MethodInfo>,
    val fields: List<String> = emptyList(),
    val refs: List<MemberRef> = emptyList(),
    val classRefs: List<String> = emptyList(),
) {
    val isInterface: Boolean get() = access and ACC_INTERFACE != 0
}

const val ACC_PRIVATE = 0x0002
const val ACC_STATIC = 0x0008
const val ACC_FINAL = 0x0010
const val ACC_INTERFACE = 0x0200
const val ACC_SYNTHETIC = 0x1000

object ClassFile {
    fun parse(bytes: ByteArray): ClassInfo? = runCatching { read(DataInputStream(bytes.inputStream())) }.getOrNull()

    private fun read(input: DataInputStream): ClassInfo? {
        if (input.readInt() != 0xCAFEBABE.toInt()) return null
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val tags = IntArray(count)
        val utf = arrayOfNulls<String>(count)
        val a = IntArray(count)
        val b = IntArray(count)
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            tags[i] = tag
            when (tag) {
                1 -> utf[i] = input.readUTF()
                3, 4 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                7, 8, 16, 19, 20 -> a[i] = input.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> { a[i] = input.readUnsignedShort(); b[i] = input.readUnsignedShort() }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> return null
            }
            i++
        }
        fun cls(idx: Int): String? = if (idx in 1 until count && tags[idx] == 7) utf[a[idx]] else null
        val access = input.readUnsignedShort()
        val name = cls(input.readUnsignedShort()) ?: return null
        val superName = cls(input.readUnsignedShort())
        val interfaces = List(input.readUnsignedShort()) { cls(input.readUnsignedShort()) ?: "" }.filter { it.isNotEmpty() }
        val fields = mutableListOf<String>()
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val n = utf[input.readUnsignedShort()] ?: ""
            val d = utf[input.readUnsignedShort()] ?: ""
            fields += "$n:$d"
            skipAttributes(input)
        }
        val methods = mutableListOf<MethodInfo>()
        repeat(input.readUnsignedShort()) {
            val acc = input.readUnsignedShort()
            val n = utf[input.readUnsignedShort()] ?: ""
            val d = utf[input.readUnsignedShort()] ?: ""
            methods += MethodInfo(n, d, acc)
            skipAttributes(input)
        }
        val refs = LinkedHashSet<MemberRef>()
        val classRefs = LinkedHashSet<String>()
        for (k in 1 until count) when (tags[k]) {
            7 -> utf[a[k]]?.takeIf { !it.startsWith("[") && it != name }?.let { classRefs += it }
            9, 10, 11 -> {
                val owner = cls(a[k]) ?: continue
                if (owner == name || owner.startsWith("[")) continue
                val nt = b[k]
                if (tags.getOrNull(nt) != 12) continue
                val n = utf[a[nt]] ?: continue
                val d = utf[b[nt]] ?: continue
                refs += MemberRef(owner, n, d, tags[k] == 9)
            }
        }
        return ClassInfo(name, superName, interfaces, access, methods, fields, refs.toList(), classRefs.toList())
    }

    private fun skipAttributes(input: DataInputStream) {
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val len = input.readInt()
            var left = len.toLong()
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= s
            }
        }
    }
}
