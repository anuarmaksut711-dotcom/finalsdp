package ecosystem;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/** Small, shaded three-quarter game sprites. Coordinates are relative to the feet. */
final class GameSprites {
    private GameSprites() { }

    static void drawDeer(Graphics2D target, int x, int y, double scale, boolean flip) {
        drawDeer(target, x, y, scale, flip, 0);
    }

    static void drawDeer(Graphics2D target, int x, int y, double scale, boolean flip, double stride) {
        Graphics2D g = sprite(target, x, y, scale, flip);
        shadow(g, 36);
        double step = Math.sin(stride) * 2.4;
        Color edge = new Color(91, 66, 43), dark = new Color(131, 91, 55);
        Color coat = new Color(195, 145, 88), light = new Color(221, 176, 113);
        Color cream = new Color(233, 215, 174);

        // Far legs are cooler and slightly shorter, giving the body an oblique stance.
        shape(g, curve(-11, -15, -9, -15, -10, -7, -10 + step, 1,
            -13 + step, 2, -13, -6, -14, -13), dark, null);
        shape(g, curve(5, -15, 8, -15, 8, -8, 7 - step, 2,
            4 - step, 2, 4, -6, 4, -13), dark, null);
        hoof(g, -12 + step, 1, 3.7, 2.1);
        hoof(g, 5.2 - step, 1, 3.8, 2.1);

        Path2D body = new Path2D.Double();
        body.moveTo(-17, -20); body.curveTo(-13, -27, 0, -27, 10, -22);
        body.curveTo(14, -19, 14, -11, 9, -8); body.curveTo(2, -4, -11, -7, -16, -12);
        body.curveTo(-19, -14, -20, -18, -17, -20); body.closePath();
        shape(g, body, coat, edge);
        Path2D back = new Path2D.Double(); back.moveTo(-17, -20);
        back.curveTo(-10, -26, 1, -25, 10, -21); back.curveTo(5, -18, -5, -16, -17, -17); back.closePath();
        shape(g, back, light, null);
        Path2D belly = new Path2D.Double(); belly.moveTo(-11, -10);
        belly.curveTo(-5, -12, 5, -13, 11, -16); belly.curveTo(13, -10, 5, -6, -3, -7);
        belly.curveTo(-7, -7, -9, -8, -11, -10); belly.closePath();
        shape(g, belly, new Color(164, 119, 74), null);
        // White rump and raised short tail remain readable against grass.
        shape(g, curve(-18, -19, -23, -23, -24, -18, -20, -15,
            -17, -15, -16, -17, -18, -19), cream, edge);
        shape(g, new Ellipse2D.Double(-18, -17, 5, 8), new Color(230, 208, 158), null);

        // Near legs, knee highlights and dark cloven hooves.
        shape(g, curve(-9, -11, -6, -12, -5, -6, -6 - step, 5,
            -10 - step, 6, -10, -3, -11, -9), light, edge);
        shape(g, curve(8, -13, 11, -13, 12, -6, 12 + step, 5,
            8 + step, 6, 7, -3, 7, -10), light, edge);
        hoof(g, -8.6 - step, 4.3, 4.6, 2.7);
        hoof(g, 9.3 + step, 4.3, 4.8, 2.7);

        // Upright tapered neck and a long muzzle distinguish the deer from the old mascots.
        Path2D neck = new Path2D.Double(); neck.moveTo(5, -14);
        neck.curveTo(8, -23, 8, -30, 12, -34); neck.lineTo(17, -31);
        neck.curveTo(15, -23, 17, -16, 11, -11); neck.closePath();
        shape(g, neck, coat, edge);
        Path2D throat = new Path2D.Double(); throat.moveTo(15, -31);
        throat.curveTo(11, -25, 14, -18, 11, -13); throat.lineTo(8, -14);
        throat.curveTo(10, -24, 10, -31, 12, -33); throat.closePath();
        shape(g, throat, cream, null);
        Path2D earBack = new Path2D.Double(); earBack.moveTo(13, -34);
        earBack.curveTo(7, -34, 5, -40, 5, -41); earBack.curveTo(11, -42, 15, -38, 15, -35); earBack.closePath();
        shape(g, earBack, dark, edge);
        Path2D ear = new Path2D.Double(); ear.moveTo(16, -35);
        ear.curveTo(19, -41, 23, -42, 24, -41); ear.curveTo(24, -36, 20, -33, 17, -33); ear.closePath();
        shape(g, ear, light, edge);
        g.setColor(new Color(159, 116, 80)); g.drawLine(18, -36, 22, -39);
        Path2D head = new Path2D.Double(); head.moveTo(11, -36);
        head.curveTo(17, -39, 20, -35, 20, -31); head.lineTo(24, -28);
        head.curveTo(24, -25, 20, -25, 17, -27); head.lineTo(11, -31); head.closePath();
        shape(g, head, light, edge);
        shape(g, curve(16, -30, 20, -29, 21, -27, 24, -28,
            24, -25, 19, -25, 16, -28), cream, null);
        g.setColor(new Color(54, 47, 37)); g.fill(new Ellipse2D.Double(17.6, -33.7, 1.9, 2.2));
        g.fill(new Ellipse2D.Double(22, -28.6, 2.9, 2));
        g.setColor(new Color(246, 224, 178)); g.fill(new Ellipse2D.Double(17.7, -34, .7, .7));
        // Paired antlers have a separate branching silhouette, never a triangle marker.
        g.setColor(new Color(107, 84, 54));
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line(12, -36, 10, -41, 9, -47));
        g.draw(line(10, -42, 6, -44, 5, -47)); g.draw(line(9.5, -44, 13, -46, 14, -48));
        g.draw(line(17, -37, 18, -42, 18, -48));
        g.draw(line(18, -43, 22, -45, 23, -48)); g.draw(line(18, -45, 15, -47));
        g.setColor(new Color(242, 216, 166, 155));
        for (int i = 0; i < 4; i++) g.fill(new Ellipse2D.Double(-10 + i * 4, -21 + i % 2 * 3, 1.9, 1.3));
        g.dispose();
    }

    static void drawWolf(Graphics2D target, int x, int y, double scale, boolean flip) {
        drawWolf(target, x, y, scale, flip, 0);
    }

    static void drawWolf(Graphics2D target, int x, int y, double scale, boolean flip, double stride) {
        Graphics2D g = sprite(target, x, y, scale, flip);
        shadow(g, 43);
        double step = Math.sin(stride) * 2.2;
        Color edge = new Color(51, 64, 68), dark = new Color(62, 77, 82);
        Color coat = new Color(103, 123, 128), top = new Color(134, 155, 154), pale = new Color(191, 204, 190);
        // Downward bushy tail, with a lit ridge and darker tip.
        Path2D tail = new Path2D.Double(); tail.moveTo(-13, -18);
        tail.curveTo(-22, -18, -24, -12, -28, -7); tail.curveTo(-25, -5, -20, -6, -18, -10);
        tail.curveTo(-14, -9, -11, -13, -13, -18); tail.closePath();
        shape(g, tail, dark, edge);
        shape(g, curve(-14, -17, -20, -16, -24, -11, -26, -9,
            -20, -10, -18, -13, -13, -14), top, null);
        // Far legs.
        leg(g, -12, -10, -13 + step, 2, dark, edge);
        leg(g, 4, -11, 4 - step, 3, dark, edge);
        Path2D body = new Path2D.Double(); body.moveTo(-18, -17);
        body.curveTo(-16, -24, -3, -24, 8, -19); body.curveTo(13, -17, 13, -10, 8, -6);
        body.curveTo(3, -3, -11, -5, -15, -9); body.curveTo(-18, -11, -19, -14, -18, -17); body.closePath();
        shape(g, body, coat, edge);
        Path2D ridge = new Path2D.Double(); ridge.moveTo(-17, -18);
        ridge.curveTo(-10, -24, -1, -22, 8, -19); ridge.lineTo(12, -14);
        ridge.curveTo(1, -16, -6, -16, -16, -14); ridge.closePath();
        shape(g, ridge, top, null);
        Path2D flank = new Path2D.Double(); flank.moveTo(-13, -11);
        flank.curveTo(-6, -12, 2, -11, 9, -14); flank.curveTo(12, -7, 5, -4, -3, -5);
        flank.curveTo(-8, -5, -11, -7, -13, -11); flank.closePath();
        shape(g, flank, new Color(83, 104, 110), null);
        // Near legs have a hind hock, lighter front and a broad paw.
        Path2D hind = new Path2D.Double(); hind.moveTo(-11, -11); hind.lineTo(-7, -10);
        hind.lineTo(-9, -3); hind.lineTo(-7 - step, 3); hind.lineTo(-7 - step, 6);
        hind.lineTo(-12 - step, 6); hind.lineTo(-12, 1); hind.lineTo(-14, -5); hind.closePath();
        shape(g, hind, coat, edge);
        leg(g, 8, -11, 9 + step, 6, top, edge);
        hoof(g, -10.8 - step, 4.2, 5.2, 2.5); hoof(g, 8.3 + step, 4.2, 5.8, 2.5);
        // Dense mane rises into an angular, natural head profile.
        Path2D mane = new Path2D.Double(); mane.moveTo(5, -10); mane.lineTo(3, -17);
        mane.lineTo(6, -22); mane.lineTo(5, -25); mane.lineTo(11, -25);
        mane.curveTo(18, -21, 18, -14, 12, -8); mane.closePath();
        shape(g, mane, top, edge);
        Path2D head = new Path2D.Double(); head.moveTo(8, -22);
        head.lineTo(8, -31); head.lineTo(14, -26); head.lineTo(20, -30);
        head.lineTo(23, -22); head.lineTo(27, -17); head.lineTo(23, -13);
        head.curveTo(20, -10, 12, -13, 10, -16); head.closePath();
        shape(g, head, coat, edge);
        shape(g, poly(9.5, -28, 10.5, -23, 13, -24), new Color(61, 74, 77), null);
        shape(g, poly(19.4, -27.3, 17.5, -23, 21, -22), new Color(61, 74, 77), null);
        Path2D cheek = new Path2D.Double(); cheek.moveTo(11, -18);
        cheek.curveTo(15, -18, 17, -15, 19, -14); cheek.lineTo(23, -18);
        cheek.lineTo(27, -17); cheek.lineTo(23, -13); cheek.curveTo(17, -10, 13, -12, 11, -18); cheek.closePath();
        shape(g, cheek, pale, null);
        Path2D chest = new Path2D.Double(); chest.moveTo(11, -13);
        chest.curveTo(14, -13, 14, -9, 10, -5); chest.lineTo(7, -9); chest.closePath();
        shape(g, chest, pale, null);
        g.setColor(new Color(35, 48, 50)); g.fill(new Ellipse2D.Double(18, -21, 2.1, 2));
        g.fill(new Ellipse2D.Double(24.2, -17.5, 3.3, 2.3));
        g.setColor(new Color(229, 208, 130)); g.fill(new Ellipse2D.Double(17.9, -21.4, .9, .9));
        g.setColor(new Color(175, 193, 183)); g.setStroke(new BasicStroke(.65f));
        g.drawLine(14, -22, 17, -23); g.drawLine(-10, -19, -5, -19);
        g.dispose();
    }

    private static Graphics2D sprite(Graphics2D target, int x, int y, double scale, boolean flip) {
        Graphics2D g = (Graphics2D) target.create(); g.translate(x, y); g.scale(flip ? -scale : scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setStroke(new BasicStroke(.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); return g;
    }
    private static void shadow(Graphics2D g, int width) {
        for (int i = 3; i >= 0; i--) {
            g.setColor(new Color(4, 24, 27, 13 + (3 - i) * 5));
            g.fill(new Ellipse2D.Double(-width / 2.0 - i + 2, 1 - i * .35, width + i * 2, 5 + i * .7));
        }
    }
    private static void hoof(Graphics2D g, double x, double y, double w, double h) {
        g.setColor(new Color(56, 53, 44)); g.fill(new Ellipse2D.Double(x, y, w, h));
    }
    private static void leg(Graphics2D g, double x, double y, double footX, double footY, Color fill, Color edge) {
        Path2D p = new Path2D.Double(); p.moveTo(x - 2, y); p.lineTo(x + 2, y);
        p.lineTo(footX + 1.6, footY); p.lineTo(footX - 2.2, footY); p.closePath(); shape(g, p, fill, edge);
    }
    private static Path2D curve(double startX, double startY, double... points) {
        Path2D path = new Path2D.Double(); path.moveTo(startX, startY);
        for (int i = 0; i + 5 < points.length; i += 6) path.curveTo(points[i], points[i + 1], points[i + 2], points[i + 3], points[i + 4], points[i + 5]);
        path.closePath(); return path;
    }
    private static Path2D line(double... points) {
        Path2D p = new Path2D.Double(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]); return p;
    }
    private static Path2D poly(double... points) {
        Path2D p = new Path2D.Double(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]); p.closePath(); return p;
    }
    private static void shape(Graphics2D g, Shape shape, Color fill, Color edge) {
        g.setColor(fill); g.fill(shape); if (edge != null) { g.setColor(edge); g.draw(shape); }
    }
}

