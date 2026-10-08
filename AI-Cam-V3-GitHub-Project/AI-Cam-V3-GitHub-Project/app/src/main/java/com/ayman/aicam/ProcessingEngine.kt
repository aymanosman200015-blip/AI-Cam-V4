package com.ayman.aicam

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** V3 original image pipeline. It does not contain Samsung/Google/Apple proprietary ISP code. */
object ProcessingEngine {
    fun process(frames: List<Bitmap>, profile: Profile, mode: CameraMode, portrait: Boolean = false): Bitmap {
        require(frames.isNotEmpty())
        val base = when {
            frames.size == 1 -> frames.first().copy(Bitmap.Config.ARGB_8888, true)
            mode == CameraMode.NIGHT -> mergeTemporal(frames, true)
            else -> mergeExposure(frames)
        }
        val tuned = tune(base, profile, mode)
        return if (portrait) portraitBlur(tuned) else tuned
    }

    private fun mergeExposure(frames: List<Bitmap>): Bitmap {
        val w = frames.minOf { it.width }; val h = frames.minOf { it.height }
        val normalized = frames.map { if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true) }
        val buffers = Array(normalized.size) { IntArray(w * h) }
        normalized.forEachIndexed { i, b -> b.getPixels(buffers[i], 0, w, 0, 0, w, h) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val px = IntArray(w * h)
        for (p in px.indices) {
            var wr = 0f; var wg = 0f; var wb = 0f; var ws = 0f
            for (i in buffers.indices) {
                val c = buffers[i][p]; val r = Color.red(c).toFloat(); val g = Color.green(c).toFloat(); val b = Color.blue(c).toFloat()
                val y = .2126f*r + .7152f*g + .0722f*b
                val weight = 1f - min(1f, kotlin.math.abs(y - 128f) / 180f)
                wr += r*weight; wg += g*weight; wb += b*weight; ws += weight
            }
            val r = if (ws == 0f) 0f else wr/ws; val g = if (ws == 0f) 0f else wg/ws; val b = if (ws == 0f) 0f else wb/ws
            px[p] = Color.rgb(clamp(toneMap(r)), clamp(toneMap(g)), clamp(toneMap(b)))
        }
        out.setPixels(px, 0, w, 0, 0, w, h); normalized.forEach { if (it !== frames.first()) it.recycle() }; return out
    }

    private fun mergeTemporal(frames: List<Bitmap>, night: Boolean): Bitmap {
        val w = frames.minOf { it.width }; val h = frames.minOf { it.height }
        val normalized = frames.map { if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true) }
        val buffers = Array(normalized.size) { IntArray(w * h) }; normalized.forEachIndexed { i,b -> b.getPixels(buffers[i],0,w,0,0,w,h) }
        val out = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); val px = IntArray(w*h)
        for (p in px.indices) {
            val rs = IntArray(buffers.size); val gs = IntArray(buffers.size); val bs = IntArray(buffers.size)
            for (i in buffers.indices) { val c=buffers[i][p]; rs[i]=Color.red(c); gs[i]=Color.green(c); bs[i]=Color.blue(c) }
            rs.sort(); gs.sort(); bs.sort(); var r=rs[rs.size/2].toFloat(); var g=gs[gs.size/2].toFloat(); var b=bs[bs.size/2].toFloat()
            val lum=.2126f*r+.7152f*g+.0722f*b; val lift=if(lum<150f) 1.16f+(150f-lum)/1600f else 1f
            r*=lift; g*=lift; b*=lift
            px[p]=Color.rgb(clamp(r),clamp(g),clamp(b))
        }
        out.setPixels(px,0,w,0,0,w,h); normalized.forEach { if (it !== frames.first()) it.recycle() }; return out
    }

    private fun tune(src: Bitmap, profile: Profile, mode: CameraMode): Bitmap {
        val out=src.copy(Bitmap.Config.ARGB_8888,true); val px=IntArray(out.width*out.height); out.getPixels(px,0,out.width,0,0,out.width,out.height)
        val sat=when(profile){Profile.S->1.10f;Profile.G->1.02f;Profile.IPHONE->1.00f}; val contrast=when(profile){Profile.S->1.08f;Profile.G->1.03f;Profile.IPHONE->1.045f}
        val warm=when(profile){Profile.S->2.2f;Profile.G->0f;Profile.IPHONE->.6f}
        for(i in px.indices){ val c=px[i]; var r=Color.red(c).toFloat(); var g=Color.green(c).toFloat(); var b=Color.blue(c).toFloat(); val y=.2126f*r+.7152f*g+.0722f*b
            val shadow=if(mode==CameraMode.NIGHT&&y<150f) 1.08f+(150f-y)/1800f else 1f; r*=shadow;g*=shadow;b*=shadow
            val gray=.299f*r+.587f*g+.114f*b; r=gray+(r-gray)*sat;g=gray+(g-gray)*sat;b=gray+(b-gray)*sat
            r=128f+(r-128f)*contrast+warm; g=128f+(g-128f)*contrast; b=128f+(b-128f)*contrast-warm
            px[i]=Color.rgb(clamp(r),clamp(g),clamp(b)) }
        out.setPixels(px,0,out.width,0,0,out.width,out.height); return out
    }

    private fun portraitBlur(src: Bitmap): Bitmap {
        val w=src.width; val h=src.height; val out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); val srcPx=IntArray(w*h);src.getPixels(srcPx,0,w,0,0,w,h);val outPx=srcPx.copyOf();val cx=w/2f;val cy=h*.47f;val rx=w*.30f;val ry=h*.34f
        for(y in 2 until h-2) for(x in 2 until w-2){val dx=(x-cx)/rx;val dy=(y-cy)/ry;if(dx*dx+dy*dy>1.0f){val i=y*w+x;var r=0;var g=0;var b=0;for(yy=-2..2)for(xx=-2..2){val c=srcPx[(y+yy)*w+x+xx];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c)};outPx[i]=Color.rgb(r/25,g/25,b/25)}}
        out.setPixels(outPx,0,w,0,0,w,h);return out
    }
    private fun toneMap(v:Float)=255f*(1f-exp(-v/255f*1.25f))
    private fun clamp(v:Float)=min(255,max(0,v.toInt()))
}
