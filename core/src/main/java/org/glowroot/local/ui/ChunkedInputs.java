/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class ChunkedInputs {

    private ChunkedInputs() {}

    private abstract static class BaseChunkedInput {

        private boolean hasSentTerminatingChunk;

        public boolean hasNextChunk() {
            return !hasSentTerminatingChunk;
        }

        @Nullable
        public Object nextChunk() throws IOException {
            if (hasMoreBytes()) {
                return readNextChunk();
            } else if (!hasSentTerminatingChunk) {
                // chunked transfer encoding must be terminated by a final chunk of length zero
                hasSentTerminatingChunk = true;
                return null;
                // return new DefaultHttpChunk(ChannelBuffers.EMPTY_BUFFER);
            } else {
                return null;
            }
        }

        public boolean isEndOfInput() {
            return hasSentTerminatingChunk;
        }

        protected abstract boolean hasMoreBytes() throws IOException;

        protected abstract Object readNextChunk() throws IOException;

        private static boolean hasMoreBytes(PushbackReader reader) throws IOException {
            int b = reader.read();
            if (b == -1) {
                return false;
            } else {
                reader.unread(b);
                return true;
            }
        }

        private static int readFully(Reader reader, char[] buffer) throws IOException {
            int total = 0;
            while (true) {
                int n = reader.read(buffer, total, buffer.length - total);
                if (n == -1) {
                    break;
                }
                total += n;
                if (total == buffer.length) {
                    break;
                }
            }
            return total;
        }
    }

}
