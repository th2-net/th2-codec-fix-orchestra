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
import io.fixprotocol._2020.orchestra.repository.ComponentRefType;
import io.fixprotocol._2020.orchestra.repository.ComponentType;
import io.fixprotocol._2020.orchestra.repository.Datatype;
import io.fixprotocol._2020.orchestra.repository.GroupRefType;
import io.fixprotocol._2020.orchestra.repository.GroupType;
import io.fixprotocol._2020.orchestra.repository.MessageType;
import io.fixprotocol._2020.orchestra.repository.Repository;
import io.fixprotocol.orchestra.model.quickfix.RepositoryAccessor;
import org.apache.commons.collections4.map.LRUMap;

import java.util.List;
import java.util.Objects;

public class RepositoryCache extends RepositoryAccessor {

    private final LRUMap<GroupRefType, GroupType> groupCache;
    private final LRUMap<ComponentRefType, ComponentType> componentCache;
    private final LRUMap<BinaryKey<Integer, String>, String> fieldDataTypeCache;
    private final LRUMap<String, Datatype> datatypeCache;
    private final LRUMap<BinaryKey<String, String>, CodeSetType> codeSetTypeCache;
    private final LRUMap<MessageType, List<Object>> messageMembersCache;

    public RepositoryCache(Repository repository, int cacheSize) {
        super(repository);
        fieldDataTypeCache = new LRUMap<>(cacheSize);
        codeSetTypeCache = new LRUMap<>(cacheSize);
        groupCache = new LRUMap<>(cacheSize);
        componentCache = new LRUMap<>(cacheSize);
        datatypeCache = new LRUMap<>(cacheSize);
        messageMembersCache = new LRUMap<>(cacheSize);
    }

    public List<Object> getMessageMembers(MessageType messageType) {
        return messageMembersCache.computeIfAbsent(messageType, super::getMessageMembers);
    }

    public CodeSetType getCodeset(String name, String scenario) {
        return codeSetTypeCache.computeIfAbsent(new BinaryKey<>(name, scenario), k -> super.getCodeset(k.first, k.second));
    }

    public Datatype getDatatype(String datatypeName) {
        return datatypeCache.computeIfAbsent(datatypeName, super::getDatatype);
    }

    public String getFieldDatatype(int id, String scenario) {
        return fieldDataTypeCache.computeIfAbsent(new BinaryKey<>(id, scenario), k -> super.getFieldDatatype(k.first, k.second));
    }

    public GroupType getGroupType(GroupRefType groupRefType) {
        return groupCache.computeIfAbsent(groupRefType, super::getGroup);
    }

    public ComponentType getComponentType(ComponentRefType componentRefType) {
        return componentCache.computeIfAbsent(componentRefType, super::getComponent);
    }


    private static class BinaryKey<T, K> {
        private final T first;
        private final K second;

        public BinaryKey(T first, K second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BinaryKey<?, ?>)) return false;
            BinaryKey<?, ?> key = (BinaryKey<?, ?>) o;
            if (!key.first.equals(first)) return false;
            return key.second.equals(second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }
}