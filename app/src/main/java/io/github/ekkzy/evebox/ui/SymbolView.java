package io.github.ekkzy.evebox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Native vector symbols authored for Eve Box; no bitmap masks or draw-time I/O. */
public final class SymbolView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private String symbol;
    private int color;
    private float pointSize;

    public SymbolView(Context context,AttributeSet attributes){this(context,"settings",0xfff5f7fc);}

    public SymbolView(Context context, String symbol, int color) {
        super(context); this.symbol = symbol; this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setColor(int color) { this.color = color; invalidate(); }
    /** Rebind a weather glyph without replacing its View or Activity-owned surface. */
    public void setSymbol(String symbol) {
        if (symbol != null && !symbol.equals(this.symbol)) { this.symbol = symbol; invalidate(); }
    }
    /** Optional glyph edge in the host's local pixels. */
    public void setPointSize(float pointSize) {
        this.pointSize = Float.isFinite(pointSize) ? Math.max(0, pointSize) : 0;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        boolean compact = "apps".equals(symbol) || "settings".equals(symbol);
        float edge = pointSize > 0 ? Math.min(pointSize, Math.min(getWidth(), getHeight()))
                : Math.min(getWidth(), getHeight()) * (compact ? .66f : 1f);
        canvas.translate((getWidth()-edge)/2f, (getHeight()-edge)/2f);
        canvas.scale(edge/32f, edge/32f);
        p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.1f);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        if (outline(canvas)) { canvas.restore(); return; }
        switch (symbol) {
            case "apps":
                p.setStyle(Paint.Style.FILL);
                for (int row=0;row<3;row++) for(int column=0;column<3;column++) {
                    float x=3+column*10,y=3+row*10;
                    canvas.drawRoundRect(x,y,x+6,y+6,2,2,p);
                }
                break;
            case "settings":
                p.setStyle(Paint.Style.FILL);path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                for(int i=0;i<64;i++){
                    double angle=(i-2)*Math.PI/32;
                    float radius=(i%8>=2&&i%8<=5)?15:11.5f;
                    float x=16+(float)Math.cos(angle)*radius,y=16+(float)Math.sin(angle)*radius;
                    if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
                }
                path.close();path.addCircle(16,16,4.5f,Path.Direction.CW);canvas.drawPath(path,p);
                path.setFillType(Path.FillType.WINDING);break;
            case "wifi":
                canvas.drawArc(1, 4, 31, 29, 218, 104, false, p);
                canvas.drawArc(7, 12, 25, 29, 218, 104, false, p);
                p.setStyle(Paint.Style.FILL); canvas.drawCircle(16, 25, 2.3f, p); break;
            case "bluetooth":
                path.reset(); path.moveTo(16, 2); path.lineTo(25, 10); path.lineTo(7, 23);
                path.moveTo(7, 8); path.lineTo(25, 22); path.lineTo(16, 30); path.lineTo(16, 2);
                canvas.drawPath(path, p); break;
            case "theme":
                canvas.drawCircle(16, 16, 13, p); p.setStyle(Paint.Style.FILL);
                canvas.drawArc(3, 3, 29, 29, 90, 180, false, p); break;
            case "tv":
                canvas.drawRoundRect(2, 4, 30, 24, 3, 3, p); canvas.drawLine(10, 29, 22, 29, p);
                canvas.drawLine(16, 24, 16, 29, p); canvas.drawLine(7, 12, 25, 12, p);
                canvas.drawLine(11, 8, 11, 16, p); canvas.drawLine(21, 16, 21, 20, p); break;
            case "weather":
                cloud(canvas);break;
            case "sun":
                canvas.drawCircle(16,16,7,p);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(16+(float)Math.cos(a)*11,16+(float)Math.sin(a)*11,16+(float)Math.cos(a)*15,16+(float)Math.sin(a)*15,p);}break;
            case "moon":
                p.setStyle(Paint.Style.FILL);path.reset();path.moveTo(22,2);path.cubicTo(4,2,0,20,13,28);path.cubicTo(20,32,29,27,31,20);path.cubicTo(15,24,10,10,22,2);path.close();canvas.drawPath(path,p);break;
            case "fog":
                canvas.drawLine(3,9,29,9,p);canvas.drawLine(7,16,25,16,p);canvas.drawLine(3,23,29,23,p);break;
            case "rain":
                canvas.save();canvas.translate(0,-3);cloud(canvas);canvas.restore();p.setStyle(Paint.Style.STROKE);
                canvas.drawLine(8,25,6,30,p);canvas.drawLine(17,25,15,30,p);canvas.drawLine(26,25,24,30,p);break;
            case "snow":
                canvas.save();canvas.translate(0,-4);cloud(canvas);canvas.restore();p.setStyle(Paint.Style.STROKE);
                canvas.drawLine(10,23,10,31,p);canvas.drawLine(6,27,14,27,p);canvas.drawLine(23,23,23,31,p);canvas.drawLine(19,27,27,27,p);break;
            case "storm":
                canvas.save();canvas.translate(0,-4);cloud(canvas);canvas.restore();p.setStyle(Paint.Style.FILL);
                path.reset();path.moveTo(17,18);path.lineTo(12,27);path.lineTo(17,26);path.lineTo(15,32);path.lineTo(23,22);path.lineTo(18,23);path.close();canvas.drawPath(path,p);break;
            case "back": canvas.drawLine(21, 6, 10, 16, p); canvas.drawLine(10, 16, 21, 26, p); break;
            case "play":
                p.setStyle(Paint.Style.FILL); path.reset(); path.moveTo(11, 7); path.lineTo(26, 16); path.lineTo(11, 25); path.close(); canvas.drawPath(path, p); break;
            case "sound":
                path.reset(); path.moveTo(3, 12); path.lineTo(9, 12); path.lineTo(17, 5); path.lineTo(17, 27); path.lineTo(9, 20); path.lineTo(3, 20); path.close(); canvas.drawPath(path, p);
                canvas.drawArc(13, 6, 29, 26, -64, 128, false, p); break;
            case "language":
                canvas.drawCircle(16, 16, 13, p); canvas.drawOval(10, 3, 22, 29, p);
                canvas.drawLine(4, 12, 28, 12, p); canvas.drawLine(4, 21, 28, 21, p); break;
            case "info":
                canvas.drawCircle(16, 16, 13, p); canvas.drawLine(16, 15, 16, 24, p);
                p.setStyle(Paint.Style.FILL); canvas.drawCircle(16, 9, 1.5f, p); break;
            case "more":
                p.setStyle(Paint.Style.FILL); for (int x = 5; x < 30; x += 11) canvas.drawCircle(x, 16, 2.7f, p); break;
            case "star":
                path.reset(); for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5; float r = i % 2 == 0 ? 14 : 6;
                    float x = 16 + (float) Math.cos(a) * r, y = 16 + (float) Math.sin(a) * r;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close(); canvas.drawPath(path, p); break;
            case "image":
                canvas.drawRoundRect(2, 3, 30, 29, 3, 3, p); canvas.drawCircle(10, 11, 3, p);
                path.reset(); path.moveTo(3, 25); path.lineTo(13, 16); path.lineTo(19, 22); path.lineTo(25, 16); path.lineTo(30, 22); canvas.drawPath(path, p); break;
            default:
                canvas.drawCircle(16, 16, 9, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    canvas.drawLine(16 + (float)Math.cos(a)*10,16 + (float)Math.sin(a)*10,
                            16 + (float)Math.cos(a)*14,16 + (float)Math.sin(a)*14,p);
                }
                canvas.drawCircle(16, 16, 3, p);
        }
        canvas.restore();
    }
    private void cloud(Canvas canvas){p.setStyle(Paint.Style.FILL);canvas.drawCircle(12,14,8,p);canvas.drawCircle(21,17,7,p);canvas.drawRoundRect(2,15,29,25,5,5,p);}

    /** Separate Home glyphs keep the existing common symbols' geometry unchanged. */
    private boolean outline(Canvas canvas) {
        p.setStrokeWidth(1.6f);
        switch (symbol) {
            case "apps-outline":
                canvas.drawRoundRect(5,5,13,13,2.2f,2.2f,p);canvas.drawRoundRect(19,5,27,13,2.2f,2.2f,p);
                canvas.drawRoundRect(5,19,13,27,2.2f,2.2f,p);canvas.drawRoundRect(19,19,27,27,2.2f,2.2f,p);return true;
            case "wifi-outline":
                path.reset();path.moveTo(3,11);path.quadTo(16,0,29,11);
                path.moveTo(7.3f,16);path.quadTo(16,8,24.7f,16);
                path.moveTo(11.7f,21);path.quadTo(16,17,20.3f,21);canvas.drawPath(path,p);
                p.setStyle(Paint.Style.FILL);canvas.drawCircle(16,26,1.1f,p);return true;
            case "bluetooth-outline":
                path.reset();path.moveTo(10,8);path.lineTo(23,24);path.lineTo(16,29);path.lineTo(16,3);
                path.lineTo(23,8);path.lineTo(10,24);canvas.drawPath(path,p);return true;
            case "wallpaper-outline":
                canvas.drawRoundRect(3.5f,5,28.5f,27,5,5,p);canvas.drawCircle(21,11.5f,2,p);
                path.reset();path.moveTo(5,24);path.lineTo(13,15);path.lineTo(19,21);path.lineTo(23,17);path.lineTo(28,23);
                canvas.drawPath(path,p);return true;
            case "sliders":
                canvas.drawLine(5,9,13,9,p);canvas.drawLine(19,9,27,9,p);canvas.drawCircle(16,9,3,p);
                canvas.drawLine(5,23,19,23,p);canvas.drawLine(25,23,27,23,p);canvas.drawCircle(22,23,3,p);return true;
            case "tv-outline":
                canvas.drawRoundRect(3,5,29,24,3.5f,3.5f,p);canvas.drawLine(16,24,16,28,p);
                canvas.drawLine(11,28,21,28,p);return true;
            case "sun-outline":
                canvas.drawCircle(16,16,6,p);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(16+(float)Math.cos(a)*11,16+(float)Math.sin(a)*11,
                        16+(float)Math.cos(a)*14,16+(float)Math.sin(a)*14,p);}return true;
            case "clear-outline": canvas.drawCircle(16,16,7,p);return true;
            case "moon-outline":
                path.reset();path.moveTo(25.7f,20.5f);path.cubicTo(15,25,6,16,11.5f,6.3f);
                path.cubicTo(-1,12,7,34,21,27);path.quadTo(24,25,25.7f,20.5f);canvas.drawPath(path,p);return true;
            case "partly-cloudy-outline":
                canvas.drawCircle(10,11,5,p);canvas.drawLine(10,2,10,4,p);canvas.drawLine(2,10,4,10,p);
                canvas.drawLine(4.4f,4.4f,5.8f,5.8f,p);canvas.drawLine(16.2f,5.8f,17.6f,4.4f,p);
                outlineCloud(canvas,3);return true;
            case "cloud-outline": outlineCloud(canvas,0);return true;
            case "rain-outline":
                outlineCloud(canvas,-3);canvas.drawLine(8,25,6,29,p);canvas.drawLine(17,25,15,29,p);
                canvas.drawLine(26,25,24,29,p);return true;
            case "snow-outline":
                outlineCloud(canvas,-4);canvas.drawLine(10,24,10,30,p);canvas.drawLine(7,27,13,27,p);
                canvas.drawLine(23,24,23,30,p);canvas.drawLine(20,27,26,27,p);return true;
            case "storm-outline":
                outlineCloud(canvas,-4);path.reset();path.moveTo(18,21);path.lineTo(13,27);path.lineTo(18,26);
                path.lineTo(16,31);path.lineTo(23,23);canvas.drawPath(path,p);return true;
            case "wind":
                path.reset();path.moveTo(3,11);path.lineTo(19,11);path.cubicTo(25,11,25,3,19,3);
                path.cubicTo(16,3,15,5,15,7);path.moveTo(3,16);path.lineTo(26,16);
                path.cubicTo(30,16,30,22,26,22);path.moveTo(3,21);path.lineTo(14,21);
                path.cubicTo(20,21,20,29,14,29);path.cubicTo(11,29,10,27,10,25);canvas.drawPath(path,p);return true;
            case "drop":
                path.reset();path.moveTo(16,3);path.cubicTo(13,8,7,14,7,20);
                path.cubicTo(7,32,25,32,25,20);path.cubicTo(25,14,19,8,16,3);canvas.drawPath(path,p);return true;
            case "refresh":
                canvas.drawArc(6,6,26,26,40,300,false,p);path.reset();path.moveTo(26,5);path.lineTo(26,13);
                path.lineTo(18,13);canvas.drawPath(path,p);return true;
            default: p.setStrokeWidth(2.1f);return false;
        }
    }
    private void outlineCloud(Canvas canvas, float offsetY) {
        path.reset();path.moveTo(9,24+offsetY);path.lineTo(24,24+offsetY);
        path.cubicTo(32,24+offsetY,32,12+offsetY,24.3f,12+offsetY);
        path.cubicTo(22,3+offsetY,11,3+offsetY,9,10+offsetY);
        path.cubicTo(0,10+offsetY,0,24+offsetY,9,24+offsetY);canvas.drawPath(path,p);
    }

}
