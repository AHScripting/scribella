package dev.ownednotes.core

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

/** Version2 PRIVATE recovery fingerprint. One instance belongs to a serialized RecoveryJournal caller.
 * All metadata uses the normal serializer; geometry is length-delimited IEEE/long data, including pressure,
 * tilt, orientation, eraser points and contour boundaries. Native notebooks and version1 records are unchanged.
 */
internal class RecoveryStrokeFingerprint(private val json: Json) {
    private val sha=MessageDigest.getInstance("SHA-256")
    private val buffer=ByteArray(8192)
    private var position=0
    private val idPrefix="{\"id\":".toByteArray(Charsets.UTF_8)
    private val emptyIdPrefix="{\"id\":\"\"".toByteArray(Charsets.UTF_8)
    private val styles=LinkedHashMap<InkStroke,ByteArray>(16,.75f,true)
    private var styleBytes=0

    /** Concatenation is exactly the ordinary JSON metadata, including its escaped, quoted ID.
     * Only geometry-free styles are retained. Unusual serializer layouts use the full encoder.
     */
    private fun quotedId(value:String):ByteArray {
        if(value.any {it.code !in 32..126 || it.code==34 || it.code==92})
            return json.encodeToString(String.serializer(),value).toByteArray(Charsets.UTF_8)
        // Plain printable ASCII has identical JSON bytes; retain the encoder for every escape/Unicode case.
        return ByteArray(value.length+2).also {bytes->
            bytes[0]=34;bytes[bytes.lastIndex]=34
            for(i in value.indices)bytes[i+1]=value[i].code.toByte()
        }
    }
    internal fun metadataEncoding(stroke:InkStroke):Array<ByteArray> {
        val style=stroke.copy(id="",samples=emptyList(),outlines=emptyList(),
            erasures=stroke.erasures.map {it.copy(points=emptyList())})
        var suffix=styles[style]
        if(suffix==null) {
            val encoded=json.encodeToString(InkStroke.serializer(),style).toByteArray(Charsets.UTF_8)
            if(encoded.size>32768 || encoded.size<emptyIdPrefix.size ||
                !emptyIdPrefix.indices.all {encoded[it]==emptyIdPrefix[it]}) {
                return arrayOf(json.encodeToString(InkStroke.serializer(),style.copy(id=stroke.id)).toByteArray(Charsets.UTF_8))
            }
            suffix=encoded.copyOfRange(emptyIdPrefix.size,encoded.size)
            styles[style]=suffix;styleBytes+=suffix.size
            while(styles.size>128 || styleBytes>262144) {
                val iterator=styles.entries.iterator();val eldest=iterator.next()
                styleBytes-=eldest.value.size;iterator.remove()
            }
        }
        val id=quotedId(stroke.id)
        return arrayOf(idPrefix,id,suffix)
    }
    private fun flush() {
        if(position>0)sha.update(buffer,0,position)
        position=0
    }
    private fun bytes(value:ByteArray) {
        var offset=0
        while(offset<value.size) {
            if(position==buffer.size)flush()
            val count=minOf(buffer.size-position,value.size-offset)
            value.copyInto(buffer,position,offset,offset+count);position+=count;offset+=count
        }
    }
    // Exact big-endian version2 bytes, without Android's per-float HeapByteBuffer/Bits call chain.
    // Callers reserve their whole primitive group before using these unchecked writes.
    private fun rawInt(value:Int) {
        val p=position
        buffer[p]=(value ushr 24).toByte();buffer[p+1]=(value ushr 16).toByte()
        buffer[p+2]=(value ushr 8).toByte();buffer[p+3]=value.toByte();position=p+4
    }
    private fun rawLong(value:Long) {rawInt((value ushr 32).toInt());rawInt(value.toInt())}
    private fun int(value:Int) {if(buffer.size-position<4)flush();rawInt(value)}
    private fun points(points:List<Point>) {
        int(points.size)
        for(point in points) {
            if(buffer.size-position<8)flush()
            rawInt(point.x.toRawBits());rawInt(point.y.toRawBits())
        }
    }
    fun digest(stroke:InkStroke):ByteArray {
        sha.reset();position=0
        val metadata=metadataEncoding(stroke)
        int(metadata.sumOf {it.size});metadata.forEach(::bytes)
        int(stroke.samples.size)
        for(sample in stroke.samples) {
            if(buffer.size-position<28)flush()
            rawInt(sample.x.toRawBits());rawInt(sample.y.toRawBits());rawLong(sample.timeMs)
            rawInt(sample.pressure.toRawBits());rawInt(sample.tilt.toRawBits());rawInt(sample.orientation.toRawBits())
        }
        int(stroke.outlines.size);stroke.outlines.forEach(::points)
        int(stroke.erasures.size);stroke.erasures.forEach {points(it.points)}
        flush();return sha.digest()
    }
}
