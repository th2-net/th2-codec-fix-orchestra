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

public class RepositoryCache extends RepositoryAccessor{

    private final LRUMap<GroupRefType, GroupType> groupCache;
    private final LRUMap<ComponentRefType, ComponentType> componentCache;
    private final LRUMap<BinaryKey<Integer, String>, String> fieldDataTypeCache;
    private final LRUMap<String, Datatype> datatypeCache;
    private final LRUMap<BinaryKey<String, String>, CodeSetType> codeSetTypeCache;
    private final LRUMap<MessageType, List<Object>> messageMembersCache;

    public RepositoryCache(Repository repository, int cacheSizeForFieldDatatype, int cacheSizeForCodeSetType, int cacheSizeForGroups, int cacheSizeForComponents, int cacheSizeForDatatype, int cacheSizeForMessageMembers) {
        super(repository);
        fieldDataTypeCache = new LRUMap<>(cacheSizeForFieldDatatype);
        codeSetTypeCache = new LRUMap<>(cacheSizeForCodeSetType);
        groupCache = new LRUMap<>(cacheSizeForGroups);
        componentCache = new LRUMap<>(cacheSizeForComponents);
        datatypeCache = new LRUMap<>(cacheSizeForDatatype);
        messageMembersCache = new LRUMap<>(cacheSizeForMessageMembers);
    }

    public List<Object> getMessageMembers(MessageType messageType){
        List<Object> members = messageMembersCache.get(messageType);
        if (members == null){
            members = super.getMessageMembers(messageType);
            messageMembersCache.put(messageType, members);
        }
        return members;
    }

    public CodeSetType getCodeset(String name, String scenario) {
        BinaryKey<String,String> key = new BinaryKey<>(name, scenario);
        CodeSetType codeSetType = codeSetTypeCache.get(key);
        if (codeSetType == null) {
            codeSetType = super.getCodeset(name, scenario);
            codeSetTypeCache.put(key, codeSetType);
        }
        return codeSetType;
    }

    public Datatype getDatatype(String datatypeName) {
        Datatype datatype = datatypeCache.get(datatypeName);
        if (datatype == null) {
            datatype = super.getDatatype(datatypeName);
            datatypeCache.put(datatypeName, datatype);
        }
        return datatype;
    }

    public String getFieldDatatype(int id, String scenario) {
        BinaryKey<Integer, String> key = new BinaryKey<>(id, scenario);
        String fieldDataType = fieldDataTypeCache.get(key);
        if (fieldDataType == null) {
            fieldDataType = super.getFieldDatatype(id, scenario);
            fieldDataTypeCache.put(key, fieldDataType);
        }
        return fieldDataType;
    }

    public GroupType getGroupType(GroupRefType groupRefType) {
        GroupType groupType = groupCache.get(groupRefType);
        if (groupType == null) {
            groupType = super.getGroup(groupRefType);
            groupCache.put(groupRefType, groupType);
        }
        return groupType;
    }

    public ComponentType getComponentType(ComponentRefType componentRefType) {
        ComponentType componentType = componentCache.get(componentRefType);
        if (componentType == null) {
            componentType = super.getComponent(componentRefType);
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
