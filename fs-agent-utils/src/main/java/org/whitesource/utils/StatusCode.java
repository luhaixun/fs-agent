/**
 * Copyright (C) 2017 WhiteSource Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.whitesource.utils;

/**
 *@author eugen.horovitz
 */
public enum StatusCode {

    SUCCESS(0), ERROR(-1), POLICY_VIOLATION(-2), CLIENT_FAILURE(-3), CONNECTION_FAILURE(-4), SERVER_FAILURE(-5), PRE_STEP_FAILURE(-6);

    private final int value;

    StatusCode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}