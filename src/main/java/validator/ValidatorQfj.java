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
package validator;

import io.fixprotocol._2020.orchestra.repository.CodeSetType;
import io.fixprotocol._2020.orchestra.repository.CodeType;
import io.fixprotocol._2020.orchestra.repository.ComponentRefType;
import io.fixprotocol._2020.orchestra.repository.ComponentType;
import io.fixprotocol._2020.orchestra.repository.Datatype;
import io.fixprotocol._2020.orchestra.repository.FieldRefType;
import io.fixprotocol._2020.orchestra.repository.FieldRuleType;
import io.fixprotocol._2020.orchestra.repository.GroupRefType;
import io.fixprotocol._2020.orchestra.repository.GroupType;
import io.fixprotocol._2020.orchestra.repository.MessageType;
import io.fixprotocol._2020.orchestra.repository.PresenceT;
import io.fixprotocol.orchestra.dsl.antlr.Evaluator;
import io.fixprotocol.orchestra.dsl.antlr.ScoreException;
import io.fixprotocol.orchestra.dsl.antlr.SemanticErrorListener;
import io.fixprotocol.orchestra.message.CodeSetScope;
import io.fixprotocol.orchestra.message.Validator;
import io.fixprotocol.orchestra.model.FixValue;
import io.fixprotocol.orchestra.model.PathStep;
import io.fixprotocol.orchestra.model.Scope;
import io.fixprotocol.orchestra.model.SymbolResolver;
import io.fixprotocol.orchestra.model.quickfix.MessageScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldMap;
import quickfix.FieldNotFound;
import quickfix.Group;
import quickfix.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiPredicate;

public class ValidatorQfj implements Validator<Message> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidatorQfj.class);

    private static class ErrorListener implements SemanticErrorListener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public void onError(String msg) {
            try {
                messages.put(msg);
            } catch (final InterruptedException e) {
                LOGGER.error(msg);
            }
        }

        void getErrors(Collection<String> toReceive) {
            messages.drainTo(toReceive);
        }

        boolean hasError() {
            return !messages.isEmpty();
        }
    }

    private final ErrorListener errorListener = new ErrorListener();
    private final Evaluator evaluator;


    private final BiPredicate<String, TestExceptionImpl> predicateEvaluator = new BiPredicate<>() {
        @Override
        public boolean test(String expression, TestExceptionImpl testException) {
            FixValue<?> fixValue;
            try {
                fixValue = evaluator.evaluate(expression);
                final ArrayList<String> toReceive = new ArrayList<>();
                errorListener.getErrors(toReceive);
                toReceive.forEach(testException::addDetail);

                if (fixValue == null) {
                    throw  new ScoreException("Failed to evaluate expression: " + expression);
                } else if (fixValue.getValue() == Boolean.TRUE) {
                    return true;
                }
            } catch (final ScoreException e) {
                testException.addDetail(e.getMessage());
            }
            return false;
        }
    };

    private final SymbolResolver symbolResolver;

    private final RepositoryCache cache;

    public ValidatorQfj(RepositoryCache cache, SymbolResolver symbolResolver) {
        this.symbolResolver = symbolResolver;
        evaluator = new Evaluator(symbolResolver, errorListener);
        this.cache = cache;
    }

    @Override
    public void validate(Message message, MessageType messageType) throws TestExceptionImpl {
        final TestExceptionImpl testException =
                new TestExceptionImpl(messageType.getName());
        try (final MessageScope messageScope =
                     new MessageScope(message, messageType, cache, symbolResolver, evaluator)) {
            symbolResolver.nest(new PathStep("in."), messageScope);
            try (Scope local = (Scope) symbolResolver.resolve(SymbolResolver.LOCAL_ROOT)) {
                local.nest(new PathStep(messageType.getName()), messageScope);

                final List<Object> members = cache.getMessageMembers(messageType);

                validateFieldMap(message, testException, members);
            }
        } catch (final Exception e) {
            throw new RuntimeException("Internal error", e);
        } finally {
            if (testException.hasDetails()) {
                throw testException;
            }
        }
    }

    private void validateField(FieldMap fieldMap, TestExceptionImpl testException,
                               FieldRefType fieldRefType) {
        final int id = fieldRefType.getId().intValue();
        final String scenario = fieldRefType.getScenario();
        final PresenceT presence = fieldRefType.getPresence();

        final String dataTypeString = cache.getFieldDatatype(id, scenario);
        final CodeSetType codeSet = cache.getCodeset(dataTypeString, scenario);
        if (codeSet != null) {
            symbolResolver.nest(new PathStep("^"), new CodeSetScope(codeSet));
        }
        final boolean isPresentInMessage = fieldMap.isSetField(id);

        switch (presence) {
            case CONSTANT:
            case IGNORED:
                break;
            case FORBIDDEN:
                if (isPresentInMessage) {
                    testException.addDetail("Forbidden field " + id + " is present", "FORBIDDEN", "present");
                    fillException(testException, id, scenario);
                }
                break;
            case OPTIONAL:
                // Evaluate rules if present
                final List<FieldRuleType> rules = fieldRefType.getRule();
                for (final FieldRuleType rule : rules) {
                    final String when = rule.getWhen();
                    if (predicateEvaluator.test(when, testException) && !isPresentInMessage) {
                        testException.addDetail("Missing required field " + id, "REQUIRED", "(not present)");
                        fillException(testException, id, scenario);
                    }
                }
                break;
            case REQUIRED:
                if (!isPresentInMessage) {
                    testException.addDetail("Missing required field " + id, "REQUIRED", "(not present)");
                    fillException(testException, id, scenario);
                }
                break;
        }

        if (isPresentInMessage) {
            try {
                final String value = fieldMap.getString(id);
                final String datatypeName = cache.getFieldDatatype(id, scenario);
                final Datatype datatype = cache.getDatatype(datatypeName);
                if (datatype == null) {
                    final List<CodeType> codeList = codeSet.getCode();
                    boolean matchesCode = false;
                    for (final CodeType codeType : codeList) {
                        if (value.equals(codeType.getValue())) {
                            matchesCode = true;
                            break;
                        }
                    }
                    if (!matchesCode) {
                        testException.addDetail("Invalid code in field " + id,
                                "in codeSet " + codeSet.getName(), value);
                        fillException(testException, id, scenario);
                    }

                }
            } catch (final FieldNotFound e) {
                // already tested for presence
            }
        }
    }

    private void fillException(TestExceptionImpl ex, int tag, String scenario) {
        ex.getTags().add(tag);
        ex.setScenario(scenario);
    }

    private void validateFieldMap(FieldMap fieldMap, TestExceptionImpl testException,
                                  List<Object> members) {
        for (final Object member : members) {
            if (member instanceof FieldRefType) {
                final FieldRefType fieldRefType = (FieldRefType) member;
                validateField(fieldMap, testException, fieldRefType);
            } else if (member instanceof GroupRefType) {
                final GroupRefType groupRefType = (GroupRefType) member;
                final GroupType groupType = cache.getGroupType(groupRefType);
                final List<Group> groups = fieldMap.getGroups(groupType.getNumInGroup().getId().intValue());
                for (final Group group : groups) {
                    validateFieldMap(group, testException, groupType.getComponentRefOrGroupRefOrFieldRef());
                }
            } else if (member instanceof ComponentRefType) {
                final ComponentRefType componentRefType = (ComponentRefType) member;
                final ComponentType component = cache.getComponentType(componentRefType);
                if (!component.getName().equals("StandardHeader")
                        && !component.getName().equals("StandardTrailer"))
                    validateFieldMap(fieldMap, testException,
                            component.getComponentRefOrGroupRefOrFieldRef());
            }
        }
    }
}
