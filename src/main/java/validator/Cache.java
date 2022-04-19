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
import io.fixprotocol.orchestra.model.quickfix.RepositoryAccessor;
import org.apache.commons.collections4.map.LRUMap;

import java.util.List;
import java.util.Objects;

public class Cache {

    private final RepositoryAccessor repositoryAdapter;
    private final LRUMap<GroupRefType, GroupType> groupCache = new LRUMap<>();
    private final LRUMap<ComponentRefType, ComponentType> componentCache = new LRUMap<>();
    private final LRUMap<BinaryKey<Integer, String>, String> fieldDataTypeCache = new LRUMap<>(500);
    private final LRUMap<String, Datatype> datatypeCache = new LRUMap<>(50);
    private final LRUMap<BinaryKey<String, String>, CodeSetType> codeSetTypeCache = new LRUMap<>(250);
    private final LRUMap<MessageType, List<Object>> messageMembersCache = new LRUMap<>(50);


    public Cache(RepositoryAccessor repositoryAdapter) {
        this.repositoryAdapter = repositoryAdapter;
    }

    public List<Object> getMessageMembers(MessageType messageType){
        List<Object> members = messageMembersCache.get(messageType);
        if (members == null){
            members = repositoryAdapter.getMessageMembers(messageType);
            messageMembersCache.put(messageType, members);
        }
        return members;
    }

    public CodeSetType getCodeset(String name, String scenario) {
        BinaryKey<String,String> key = new BinaryKey<>(name, scenario);
        CodeSetType codeSetType = codeSetTypeCache.get(key);
        if (codeSetType == null) {
            codeSetType = repositoryAdapter.getCodeset(name, scenario);
            codeSetTypeCache.put(key, codeSetType);
        }
        return codeSetType;
    }

    public Datatype getDatatype(String datatypeName) {
        Datatype datatype = datatypeCache.get(datatypeName);
        if (datatype == null) {
            datatype = repositoryAdapter.getDatatype(datatypeName);
            datatypeCache.put(datatypeName, datatype);
        }
        return datatype;
    }

    public String getFieldDatatype(int id, String scenario) {
        BinaryKey<Integer, String> key = new BinaryKey<>(id, scenario);
        String fieldDataType = fieldDataTypeCache.get(key);
        if (fieldDataType == null) {
            fieldDataType = repositoryAdapter.getFieldDatatype(id, scenario);
            fieldDataTypeCache.put(key, fieldDataType);
        }
        return fieldDataType;
    }

    public GroupType getGroupType(GroupRefType groupRefType) {
        GroupType groupType = groupCache.get(groupRefType);
        if (groupType == null) {
            groupType = repositoryAdapter.getGroup(groupRefType);
            groupCache.put(groupRefType, groupType);
        }
        return groupType;
    }

    public ComponentType getComponentType(ComponentRefType componentRefType) {
        ComponentType componentType = componentCache.get(componentRefType);
        if (componentType == null) {
            componentType = repositoryAdapter.getComponent(componentRefType);
            componentCache.put(componentRefType, componentType);
        }
        return componentType;
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
            if (!(o instanceof BinaryKey<?,?>)) return false;
            BinaryKey<?,?> key = (BinaryKey<?,?>) o;
            if (!key.first.equals(first)) return false;
            return key.second.equals(second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }
}
