package xyz.etkn.tv;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Right-aligned, uncropped artwork fades into the scrolling detail page. */
final class DetailBackdrop extends ImageView {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final int ink=TvActivity.INK;
    private Bitmap atmosphere;
    DetailBackdrop(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override public void setImageBitmap(Bitmap image){
        atmosphere=image==null?null:Bitmap.createScaledBitmap(image,24,Math.max(1,Math.round(24f*image.getHeight()/image.getWidth())),true);
        super.setImageBitmap(image);
    }
    @Override protected void onDraw(Canvas canvas){
        Drawable image=getDrawable();if(image==null||image.getIntrinsicWidth()<=0||image.getIntrinsicHeight()<=0)return;
        float width=getWidth(),height=getHeight(),artWidth=width*.84f,artHeight=artWidth*image.getIntrinsicHeight()/image.getIntrinsicWidth(),left=width-artWidth;
        if(atmosphere!=null){paint.setShader(null);paint.setAlpha(56);canvas.drawBitmap(atmosphere,null,new RectF(0,0,width,height),paint);paint.setAlpha(255);}
        int save=canvas.save();canvas.translate(left,0);canvas.scale(artWidth/image.getIntrinsicWidth(),artHeight/image.getIntrinsicHeight());image.setBounds(0,0,image.getIntrinsicWidth(),image.getIntrinsicHeight());image.draw(canvas);canvas.restoreToCount(save);
        int transparent=ink&0xffffff;
        paint.setShader(new LinearGradient(left,0,left+artWidth*.6f,0,new int[]{ink,ink,Color.argb(150,Color.red(ink),Color.green(ink),Color.blue(ink)),transparent},new float[]{0,.16f,.5f,1},Shader.TileMode.CLAMP));canvas.drawRect(0,0,width,height,paint);
        paint.setShader(new LinearGradient(0,0,0,Math.min(height,artHeight),new int[]{transparent,transparent,ink},new float[]{0,.45f,1},Shader.TileMode.CLAMP));canvas.drawRect(0,0,width,height,paint);paint.setShader(null);
    }
}