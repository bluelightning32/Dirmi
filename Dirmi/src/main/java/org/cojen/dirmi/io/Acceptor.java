/*
 *  Copyright 2009 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.Closeable;
import java.io.IOException;

/**
 * Asynchronously accepts channels from a remote endpoint. All channels are
 * linked to the same remote endpoint.
 *
 * @author Brian S O'Neill
 */
public interface Acceptor<C extends Closeable> extends Closeable {
    /**
     * @return local address of accepted channels or null if unknown
     */
    Object getLocalAddress();

    /**
     * Returns immediately and calls established method on listener
     * asynchronously. Only one channel is accepted per invocation of this
     * method.
     */
    void accept(AcceptListener<C> listener);

    /**
     * Prevents new channels from being accepted.
     */
    void close() throws IOException;
}
