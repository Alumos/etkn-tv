package xyz.etkn.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Shared server wallpaper, decoded and blurred off the UI thread on API 24+. */
final class AppearanceBackground extends FrameLayout {
    private final TvActivity host;
    private final ImageView image,loader;
    private String key="";
    private int ticket;
    private Bitmap panelWallpaper;
    private final ArrayList<WeakReference<View>> panels=new ArrayList<>();
    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener=this::invalidatePanels;
    AppearanceBackground(TvActivity host){
        super(host);this.host=host;setTag("server-background");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        image=new ImageView(host);image.setScaleType(ImageView.ScaleType.CENTER_CROP);addView(image,new LayoutParams(-1,-1));
        loader=new ImageView(host);loader.setVisibility(INVISIBLE);addView(loader,new LayoutParams(0,0));update();
    }
    void update(){
        setBackgroundColor(TvAppearance.background(host.appearanceDark()));
        String path=TvAppearance.current.optString("background_url");int blur=TvAppearance.current.optInt("background_blur",10);
        java.io.File local=TvAppearance.lockedBackground(host,host.api);
        String next=host.api.server+":"+host.api.session.userId()+":"+path+":"+blur+":"+(local==null?"":local.getName());if(next.equals(key))return;key=next;int request=++ticket;
        image.setImageDrawable(null);loader.setImageDrawable(null);panelWallpaper=null;invalidatePanels();
        if(path.isEmpty()){loader.setTag(next);return;}
        if(local!=null){
            loader.setTag(next);TvActivity.IO.execute(()->{
                android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(local.getPath(),options);
                options.inSampleSize=1;while(options.outWidth/options.inSampleSize>3840||options.outHeight/options.inSampleSize>2160)options.inSampleSize*=2;options.inJustDecodeBounds=false;
                Bitmap source=android.graphics.BitmapFactory.decodeFile(local.getPath(),options);if(source==null)return;
                prepare(source,blur,request,true);
            });return;
        }
        Images.thumbnail(host.api,host.api.server+path,loader,1920,1080,success->{
            if(request!=ticket||!success||!(loader.getDrawable() instanceof BitmapDrawable))return;
            Bitmap source=((BitmapDrawable)loader.getDrawable()).getBitmap();
            TvActivity.IO.execute(()->prepare(source,blur,request,false));
        });
    }
    private void prepare(Bitmap source,int blur,int request,boolean owned){
        Bitmap wallpaper=BackdropBlur.create(source,blur);
        // Only the setup overlays sample extra blur; a zero preference keeps all wallpaper sharp.
        Bitmap glass=host instanceof SetupActivity&&blur>0?(blur<20?BackdropBlur.create(source,20):wallpaper):null;
        if(owned&&wallpaper!=source)source.recycle();
        post(()->{
            if(request==ticket&&!host.isDestroyed()){
                image.setImageBitmap(wallpaper);panelWallpaper=glass;invalidatePanels();
            }else{
                if(glass!=null&&glass!=wallpaper)glass.recycle();
                if(wallpaper!=source||owned)wallpaper.recycle();
            }
        });
    }
    void panel(View view){
        panels.add(new WeakReference<>(view));view.setBackground(new WallpaperPanel(view));
    }
    private void invalidatePanels(){panels.removeIf(ref->{View view=ref.get();if(view==null)return true;view.invalidate();return false;});}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnScrollChangedListener(scrollListener);}
    @Override protected void onDetachedFromWindow(){getViewTreeObserver().removeOnScrollChangedListener(scrollListener);super.onDetachedFromWindow();}

    /** Reuses the same center crop as the wallpaper and clips to this panel, never its contents. */
    private final class WallpaperPanel extends Drawable {
        private final View view;
        private final Drawable tint=host.shape(TvActivity.PANEL,0);
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] frameAt=new int[2],panelAt=new int[2];
        private final RectF drawAt=new RectF(),clipBounds=new RectF();
        private final Path clip=new Path();
        WallpaperPanel(View view){this.view=view;}
        @Override protected void onBoundsChange(Rect bounds){
            tint.setBounds(bounds);clipBounds.set(bounds);clip.reset();clip.addRoundRect(clipBounds,host.dp(12),host.dp(12),Path.Direction.CW);
        }
        @Override public void draw(Canvas canvas){
            Bitmap sample=panelWallpaper;
            int opacity=android.graphics.Color.alpha(TvAppearance.translucent(0));
            int width=image.getWidth(),height=image.getHeight();
            if(sample!=null&&opacity>0&&opacity<255&&width>0&&height>0&&view.isAttachedToWindow()){
                getLocationInWindow(frameAt);view.getLocationInWindow(panelAt);
                float scale=Math.max((float)width/sample.getWidth(),(float)height/sample.getHeight());
                float drawnWidth=sample.getWidth()*scale,drawnHeight=sample.getHeight()*scale;
                float left=frameAt[0]-panelAt[0]+Math.round((width-drawnWidth)/2f);
                float top=frameAt[1]-panelAt[1]+Math.round((height-drawnHeight)/2f);
                drawAt.set(left,top,left+drawnWidth,top+drawnHeight);
                int saved=canvas.save();canvas.clipPath(clip);canvas.drawBitmap(sample,null,drawAt,paint);canvas.restoreToCount(saved);
            }
            tint.draw(canvas);
        }
        @Override public void setAlpha(int alpha){tint.setAlpha(alpha);paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){tint.setColorFilter(filter);paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
