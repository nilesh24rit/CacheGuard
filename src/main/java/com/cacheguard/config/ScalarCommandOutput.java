package com.cacheguard.config;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.CommandOutput;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Command output for raw execution of commands Spring Data Redis has no type
 * hint for (Redis Stack module commands such as BF.ADD/BF.EXISTS).
 * <p>
 * The default byte-array output only accepts bulk strings, and Lettuce's
 * {@code ObjectOutput} expects an aggregated reply and fails on scalars, so
 * both break when the server answers a module command with an integer
 * (RESP2) or a boolean (RESP3). This output accepts every scalar reply type
 * and exposes it as a plain Java object.
 */
public class ScalarCommandOutput extends CommandOutput<byte[], byte[], Object> {

    public ScalarCommandOutput() {
        super(ByteArrayCodec.INSTANCE, null);
    }

    @Override
    public void set(ByteBuffer bytes) {
        if (bytes == null) {
            output = null;
            return;
        }
        byte[] data = new byte[bytes.remaining()];
        bytes.get(data);
        output = new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public void set(long integer) {
        output = integer;
    }

    @Override
    public void set(double number) {
        output = number;
    }

    @Override
    public void set(boolean value) {
        output = value ? 1L : 0L;
    }

    @Override
    public Object get() {
        return output;
    }
}
