package com.pkb.util;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * float[] 与字节序列互转。
 */
public final class VecCodec {

    private VecCodec() {
    }

    public static byte[] toBytes(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4);
        buf.asFloatBuffer().put(vec);
        return buf.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }
}
