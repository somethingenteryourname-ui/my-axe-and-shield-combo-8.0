package dev.aegistitan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The 2D outline of a shield (flat top with rounded corners, curved point at the
 * bottom), measured in blocks. u = left/right, v = down/up, (0,0) = middle.
 * All the particle positions are worked out once per size and cached.
 */
final class ShieldShape {

    /** How much of the height is the pointed bottom part. */
    private static final double TAPER = 0.38;

    final double w;
    final double h;
    final double cornerRadius;
    final double emblemV;
    final double emblemR;
    final double[] outline;
    final double[] latticeA;
    final double[] latticeB;

    ShieldShape(double w, double h) {
        this.w = w;
        this.h = h;
        this.cornerRadius = Math.min(w, h) * 0.12;
        this.emblemV = h * 0.1;
        this.emblemR = Math.min(w * 0.5, h * 0.6) * 0.35;
        this.outline = buildOutline();
        double area = w * h * 0.85;
        double spacing = Math.max(1.2, Math.sqrt(area) / 9.0);
        double step = Math.max(0.35, Math.sqrt(area) / 32.0);
        this.latticeA = buildLattice(spacing, step, 1);
        this.latticeB = buildLattice(spacing, step, -1);
    }

    /** Half the width of the shield at height v, or -1 if v is outside the shield. */
    double halfWidth(double v) {
        double bottom = -h / 2;
        double top = h / 2;
        if (v < bottom || v > top) {
            return -1;
        }
        double taperTop = bottom + h * TAPER;
        if (v < taperTop) {
            double t = (v - bottom) / (taperTop - bottom);
            return (w / 2) * Math.sin(t * Math.PI / 2);
        }
        if (v > top - cornerRadius) {
            double dy = v - (top - cornerRadius);
            return w / 2 - cornerRadius + Math.sqrt(Math.max(0, cornerRadius * cornerRadius - dy * dy));
        }
        return w / 2;
    }

    boolean contains(double u, double v) {
        double hw = halfWidth(v);
        return hw >= 0 && Math.abs(u) <= hw;
    }

    /** A random point inside the shield, or null (rare). */
    double[] randomInside(Random random) {
        for (int i = 0; i < 12; i++) {
            double u = (random.nextDouble() - 0.5) * w;
            double v = (random.nextDouble() - 0.5) * h;
            if (contains(u, v)) {
                return new double[]{u, v};
            }
        }
        return null;
    }

    private double[] buildOutline() {
        List<double[]> poly = new ArrayList<>();
        int n = 200;
        for (int i = 0; i <= n; i++) {
            double v = -h / 2 + h * i / n;
            poly.add(new double[]{halfWidth(v), v});
        }
        for (int i = n; i >= 0; i--) {
            double v = -h / 2 + h * i / n;
            poly.add(new double[]{-halfWidth(v), v});
        }
        double perimeter = 0;
        for (int i = 1; i < poly.size(); i++) {
            perimeter += dist(poly.get(i - 1), poly.get(i));
        }
        double spacing = Math.max(0.28, perimeter / 220.0);

        List<Double> pts = new ArrayList<>();
        double travelled = 0;
        double nextMark = 0;
        for (int i = 1; i < poly.size(); i++) {
            double[] a = poly.get(i - 1);
            double[] b = poly.get(i);
            double seg = dist(a, b);
            if (seg <= 0) {
                continue;
            }
            while (nextMark <= travelled + seg) {
                double t = (nextMark - travelled) / seg;
                pts.add(a[0] + (b[0] - a[0]) * t);
                pts.add(a[1] + (b[1] - a[1]) * t);
                nextMark += spacing;
            }
            travelled += seg;
        }
        return toArray(pts);
    }

    /** Diagonal lines across the shield (sign 1 = "/" lines, -1 = "\" lines). */
    private double[] buildLattice(double spacing, double step, int sign) {
        List<Double> pts = new ArrayList<>();
        double du = step / Math.sqrt(2);
        double cMax = w / 2 + h / 2;
        for (double c = -cMax + spacing / 2; c <= cMax; c += spacing) {
            for (double u = -w / 2; u <= w / 2; u += du) {
                double v = sign * u + c;
                double hw = halfWidth(v);
                if (hw >= 0 && hw - Math.abs(u) > 0.2) {
                    pts.add(u);
                    pts.add(v);
                }
            }
        }
        return toArray(pts);
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static double[] toArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
