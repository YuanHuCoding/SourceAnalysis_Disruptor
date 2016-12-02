/*
 * Copyright 2012 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

/**
 * Implementors of this interface must provide a single long value
 * that represents their current cursor value.  Used during dynamic
 * add/remove of Sequences from a
 * {@link SequenceGroups#addSequences(Object, java.util.concurrent.atomic.AtomicReferenceFieldUpdater, Cursored, Sequence...)}.
 * 实现此接口的类，可以理解为，记录某个sequence的类。例如，生产者在生产消息时，需要知道当前ringBuffer下一个生产的位置，这个位置需要更新，每次更新，需要访问getCursor来定位。
 */
public interface Cursored
{
    /**
     * Get the current cursor value.
     * Cursored接口只提供了一个获取当前序列值(游标)的方法。
     * @return current cursor value
     */
    long getCursor();
}
