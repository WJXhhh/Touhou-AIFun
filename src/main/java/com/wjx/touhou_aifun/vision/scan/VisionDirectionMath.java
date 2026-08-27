package com.wjx.touhou_aifun.vision.scan;

import net.minecraft.world.phys.Vec3;

/** Pure cubemap/relative-direction math with no registry or world bootstrap dependencies. */
final class VisionDirectionMath {
    private VisionDirectionMath() {
    }

    static Vec3 rawCubemapDirection(ScanDirection face, int row, int column, int raysPerFace) {
        double u = ((column + 0.5) / raysPerFace) * 2.0 - 1.0;
        double v = ((row + 0.5) / raysPerFace) * 2.0 - 1.0;
        Vec3 direction = switch (face) {
            case FRONT -> new Vec3(u, -v, 1);
            // Minecraft yaw increases clockwise. At yaw 0 the maid faces +Z (south), so her
            // physical right is -X (west), not +X.
            case RIGHT -> new Vec3(-1, -v, u);
            case BACK -> new Vec3(-u, -v, -1);
            case LEFT -> new Vec3(1, -v, -u);
            case UP -> new Vec3(u, 1, v);
            case DOWN -> new Vec3(u, -1, -v);
            case ALL -> new Vec3(0, 0, 1);
        };
        return direction.normalize();
    }

    static Vec3 worldRayDirection(ScanDirection face, int row, int column, int raysPerFace,
                                  float yawDegrees) {
        return rotateYaw(rawCubemapDirection(face, row, column, raysPerFace), yawDegrees);
    }

    static Vec3 rotateYaw(Vec3 local, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        return new Vec3(cos * local.x - sin * local.z, local.y, sin * local.x + cos * local.z);
    }

    static String relativeDirection(double originX, double eyeY, double originZ,
                                    float yawDegrees, Vec3 point) {
        double dx = point.x - originX;
        double dy = point.y - eyeY;
        double dz = point.z - originZ;
        double yaw = Math.toRadians(yawDegrees);
        double front = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
        if (Math.abs(dy) > Math.max(Math.abs(front), Math.abs(right)) * 0.7) {
            return dy > 0 ? "up" : "down";
        }
        if (Math.abs(front) >= Math.abs(right)) {
            return front >= 0 ? "front" : "back";
        }
        return right >= 0 ? "right" : "left";
    }
}
