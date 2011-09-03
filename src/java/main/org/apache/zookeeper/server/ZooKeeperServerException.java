/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

/**
 * Thrown when an unrecoverable error occurs during the execution of a 
 * ZooKeeper server.
 */
public class ZooKeeperServerException extends RuntimeException {

    private static final long serialVersionUID = -1341862482440514576L;
    
    private int errorCode;
    
    /**
     * Construct a new runtime exception with the specified detail 
     * message and error code.
     */
    public ZooKeeperServerException(String message, int errorCode) {
        super (message);
        this.errorCode = errorCode;
    }
    
    /**
     * Construct a new runtime exception with the specified detail 
     * message, error code and cause.
     */
    public ZooKeeperServerException(String message, int errorCode, Throwable cause) {
        super (message, cause);
        this.errorCode = 0;
    }
    
    /**
     * Return an integer representation of the error
     * that occurred, or 0 when unknown.
     */
    public int getErrorCode () {
        return errorCode;
    }
}
