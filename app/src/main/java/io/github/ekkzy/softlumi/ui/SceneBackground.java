package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.util.AttributeSet;

/** Original vector landscapes. Static drawing: it never schedules its own frames. */
public final class SceneBackground extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int theme;
    private int background;
    private Shader skyShader;
    private final Shader waterShader=new LinearGradient(0,350,0,720,0xff18344b,0xff061323,Shader.TileMode.CLAMP);
    private static final int[] LIGHT_TOPS={0xfff1f0ee,0xfffaf9f7,0xfff1f1f1,0xfffafaf8};
    private static final int[] LIGHT_BOTTOMS={0xffdededb,0xffe8e7e4,0xffdedede,0xffe8e9e5};
    private static final int[] DARK_TOPS={0xff282828,0xff323230,0xff2c2c2c,0xff2a2d2a};
    private static final float[] LIGHT_MOUNTAIN={0,480,130,390,250,446,420,326,600,420,790,330,1000,430,1280,344};
    private static final float[] DARK_MOUNTAIN={0,440,200,300,380,470,670,250,840,430,1100,330,1280,440};
    private static final float[] NIGHT_MOUNTAIN={0,310,180,220,260,270,430,140,500,198,605,65,720,200,810,155,970,280,1130,196,1280,310};
    private static final float[] NIGHT_SNOW={530,153,605,65,680,151,626,121,603,134,582,115};
    private static final float[] NIGHT_FOREST={0,240,120,288,240,230,350,316,460,246,570,320,700,264,790,320,900,238,1020,286,1140,238,1280,315};
    private static final float[] NIGHT_SHORE={0,330,120,348,280,333,430,357,600,342,790,360,1000,334,1120,349,1280,322};

    public SceneBackground(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setScene(0,0); }
    public SceneBackground(Context context,AttributeSet attributes){this(context);}

    public void setScene(int theme, int background) {
        this.theme = theme;
        this.background = background;
        int index=Math.max(0,Math.min(3,background));
        if(theme==1||theme==3)skyShader=new LinearGradient(0,0,1280,720,LIGHT_TOPS[index],LIGHT_BOTTOMS[index],Shader.TileMode.CLAMP);
        else if(theme==2)skyShader=new LinearGradient(0,0,1280,720,DARK_TOPS[index],0xff101010,Shader.TileMode.CLAMP);
        else skyShader=new LinearGradient(0,0,0,720,background==1?0xff22384d:background==2?0xff182a42:0xff07162b,0xff10283d,Shader.TileMode.CLAMP);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setAlpha(255);
        canvas.save();
        canvas.scale(getWidth() / 1280f, getHeight() / 720f);
        if (theme == 1 || theme == 3) {
            paint.setShader(skyShader);
            canvas.drawRect(0, 0, 1280, 720, paint);
            paint.setShader(null);
            paint.setColor(0x0d9e9e9e);
            mountain(canvas,390,LIGHT_MOUNTAIN);
            paint.setColor(0x08b5b5b5);
            canvas.drawRect(0, 490, 1280, 720, paint);
        } else if (theme == 2) {
            paint.setShader(skyShader);
            canvas.drawRect(0, 0, 1280, 720, paint);
            paint.setShader(null);
            paint.setColor(0x10424242);
            mountain(canvas,470,DARK_MOUNTAIN);
        } else {
            paint.setShader(skyShader);
            canvas.drawRect(0, 0, 1280, 720, paint);
            paint.setShader(null);
            paint.setColor(0xff21364e);
            mountain(canvas,340,NIGHT_MOUNTAIN);
            paint.setColor(0xff6b7d91);
            polygon(canvas,NIGHT_SNOW);
            paint.setColor(0xff0e2235);
            mountain(canvas,375,NIGHT_FOREST);
            paint.setColor(0xff081b2c);
            canvas.drawRect(0, 350, 1280, 720, paint);
            paint.setShader(waterShader);
            canvas.drawRect(0, 350, 1280, 720, paint);
            paint.setShader(null);
            paint.setColor(0xff0b1c2a);
            mountain(canvas,390,NIGHT_SHORE);
            for (int x = 0; x < 1280; x += 25) {
                float y = 316 + (float) Math.sin(x * .031) * 18;
                paint.setColor(0xff071a27);
                path.reset();path.moveTo(x,y-58);path.lineTo(x-15,y+12);path.lineTo(x+15,y+12);path.close();canvas.drawPath(path,paint);
                if (x % 75 == 0) {
                    paint.setColor(0xffd9a263);
                    canvas.drawCircle(x + 6, 354, 1.8f, paint);
                    paint.setColor(0x35dca269);
                    canvas.drawRect(x + 3, 363, x + 8, 439, paint);
                }
            }
            paint.setColor(0x21000000);
            canvas.drawRect(0, 0, 1280, 720, paint);
        }
        canvas.restore();
    }

    private void mountain(Canvas c, float bottom, float[] points) {
        path.reset(); path.moveTo(0, bottom);
        for (int i = 0; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.lineTo(1280, bottom); path.close(); c.drawPath(path, paint);
    }

    private void polygon(Canvas c, float[] points) {
        path.reset(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.close(); c.drawPath(path, paint);
    }
}
