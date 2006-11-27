/*
 *  Copyright 2006 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.Socket;

/**
 * Basic connection implementation that wraps two streams or a socket.
 *
 * @author Brian S O'Neill
 */
public class BasicConnection implements Connection {
    private final InputStream mIn;
    private final OutputStream mOut;

    public BasicConnection(InputStream in, OutputStream out) {
        mIn = in;
        mOut = out;
    }

    public BasicConnection(Socket socket) throws IOException {
        mIn = socket.getInputStream();
        mOut = socket.getOutputStream();
    }

    public InputStream getInputStream() {
        return mIn;
    }

    public OutputStream getOutputStream() {
        return mOut;
    }

    public void close() throws IOException {
        mOut.close();
        mIn.close();
    }
}
