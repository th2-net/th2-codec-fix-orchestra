/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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
package com.exactpro.th2.codec.fix.orchestra.validator;

import io.fixprotocol.orchestra.message.TestException;

import java.util.ArrayList;
import java.util.List;

public class TestExceptionImpl extends TestException {

    private final String msgType;
    private final List<Integer> tags = new ArrayList<>();
    private String scenario;

    public TestExceptionImpl(String msgType) {
        super("Invalid message type " + msgType);
        this.msgType = msgType;
    }

    public String getMsgType() {
        return msgType;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public List<Integer> getTags() {
        return tags;
    }
}
