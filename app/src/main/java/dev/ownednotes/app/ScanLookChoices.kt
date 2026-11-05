package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.view.View
import android.widget.*

/** Small local processed previews; full-resolution original processing remains the existing insert operation. */
internal class ScanLookChoices(context: Context, private val photo: Bitmap, private val corners: () -> List<PointF>,
    initial: ScanProcessing.Look, private val choose: (ScanProcessing.Look) -> Unit) : LinearLayout(context) {
    private val images=mutableMapOf<ScanProcessing.Look,ImageView>()
    private val cards=mutableMapOf<ScanProcessing.Look,LinearLayout>()
    private var selected=initial
    private val refreshTask=Runnable { render() }
    init {
        orientation=HORIZONTAL;contentDescription="Scan look previews"
        ScanProcessing.Look.entries.forEach { look ->
            val picture=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="${look.label} scan preview" }
            val card=LinearLayout(context).apply {
                orientation=VERTICAL;isClickable=true;isFocusable=true;contentDescription="${look.label} scan look"
                addView(picture,LayoutParams(-1,dpi(80f)));addView(TextView(context).apply {text=look.label;textSize=12f;gravity=android.view.Gravity.CENTER},LayoutParams(-1,-2))
                setOnClickListener {selected=look;choose(look);mark()}
            }
            images[look]=picture;cards[look]=card;addView(card,LayoutParams(0,-2,1f))
        }
        mark()
    }
    fun refresh(){removeCallbacks(refreshTask);postDelayed(refreshTask,80)}
    private fun mark(){cards.forEach { (look,card)->card.isSelected=selected==look;card.background=if(card.isSelected) rounded(Glass.SELECTED,dp(Glass.CHIP)) else null;card.contentDescription="${look.label} scan look"+if(card.isSelected)", selected" else ""}}
    private fun render(){
        if(photo.isRecycled)return
        val points=corners().map {PointF(it.x,it.y)}
        images.forEach { (look,image)->runCatching {ScanProcessing.straighten(photo,points,look,maxEdge=180)}.onSuccess {image.setImageBitmap(it)}.onFailure {image.setImageDrawable(null)} }
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();refresh()}
    override fun onDetachedFromWindow(){removeCallbacks(refreshTask);images.values.forEach {it.setImageDrawable(null)};super.onDetachedFromWindow()}
}
