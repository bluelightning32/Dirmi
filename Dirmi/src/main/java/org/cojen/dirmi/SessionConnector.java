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

package org.cojen.dirmi;

import java.io.IOException;

import java.util.concurrent.TimeUnit;

/**
 * Connects to remote endpoints and establishes sessions.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface SessionConnector {
    /**
     * Returns a new session, blocking until it has been established.
     */
    Session connect() throws IOException;

    /**
     * Returns a new session, blocking until it has been established.
     *
     * @throws RemoteTimeoutException
     */
    Session connect(long timeout, TimeUnit unit) throws IOException;

    /**
     * @return remote address of connected sessions or null if unknown
     */
    Object getRemoteAddress();

    /**
     * @return local address of connected sessions or null if unknown
     */
    Object getLocalAddress();
}