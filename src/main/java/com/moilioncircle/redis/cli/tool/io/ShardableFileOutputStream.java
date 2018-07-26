/*
 * Copyright 2018-2019 Baoyi Chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moilioncircle.redis.cli.tool.io;

import com.moilioncircle.redis.cli.tool.conf.Configure;
import com.moilioncircle.redis.cli.tool.conf.NodeConfParser;
import com.moilioncircle.redis.cli.tool.util.OutputStreams;
import com.moilioncircle.redis.cli.tool.util.type.Tuple3;
import com.moilioncircle.redis.replicator.io.CRCOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.moilioncircle.redis.cli.tool.util.CRC16.crc16;
import static com.moilioncircle.redis.cli.tool.util.OutputStreams.newCRCOutputStream;
import static java.nio.file.Paths.get;

/**
 * @author Baoyi Chen
 */
public class ShardableFileOutputStream extends OutputStream {

    private byte[] key;

    private final Set<CRCOutputStream> set = new HashSet<>();
    private final Map<Short, CRCOutputStream> map = new HashMap<>();

    public ShardableFileOutputStream(String path, List<String> lines, Configure configure) {
        Function<Tuple3<String, Integer, String>, CRCOutputStream> mapper = t -> {
            return newCRCOutputStream(get(path, t.getV3() + ".rdb").toFile(), configure.getBufferSize());
        };
        new NodeConfParser(mapper).parse(lines, set, map);
        if (map.size() != 16384)
            throw new UnsupportedOperationException("slots size : " + map.size() + ", expected 16384.");
    }

    public void shard(byte[] key) {
        this.key = key;
    }

    private short slot(byte[] key) {
        if (key == null) return 0;
        int st = -1, ed = -1;
        for (int i = 0, len = key.length; i < len; i++) {
            if (key[i] == '{' && st == -1) st = i;
            if (key[i] == '}' && st >= 0) {
                ed = i;
                break;
            }
        }
        if (st >= 0 && ed >= 0 && ed > st + 1)
            return (short) (crc16(key, st + 1, ed) & 16383);
        return (short) (crc16(key) & 16383);
    }

    @Override
    public void write(int b) throws IOException {
        if (key == null) {
            for (OutputStream out : set) {
                out.write(b);
            }
        } else {
            map.get(slot(key)).write(b);
        }
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (key == null) {
            for (OutputStream out : set) {
                out.write(b, off, len);
            }
        } else {
            map.get(slot(key)).write(b, off, len);
        }
    }

    public void flush() throws IOException {
        if (key == null) {
            for (OutputStream out : set) {
                out.flush();
            }
        } else {
            map.get(slot(key)).flush();
        }
    }

    public void close() throws IOException {
        for (OutputStream out : set) {
            out.close();
        }
    }

    public void writeCRC() {
        for (CRCOutputStream out : set) {
            OutputStreams.write(0xFF, out);
            OutputStreams.write(out.getCRC64(), out);
        }
    }
}
