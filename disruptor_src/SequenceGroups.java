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

import static java.util.Arrays.copyOf;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Provides static methods for managing a {@link SequenceGroup} object.
 * 是针对SequenceGroup的帮助类，里面提供了addSequences和removeSequence方法，都是原子操作。
 */
class SequenceGroups
{
    /**
    * 原子添加sequences
    * 
    * @param holder 原子更新的域所属的类对象
    * @param updater 原子更新的域对象
    * @param cursor 定位
    * @param sequencesToAdd 要添加的sequences
    * @param <T>
    */
    static <T> void addSequences(
        final T holder,
        final AtomicReferenceFieldUpdater<T, Sequence[]> updater,
        final Cursored cursor,
        final Sequence... sequencesToAdd)
    {
        long cursorSequence;
        Sequence[] updatedSequences;
        Sequence[] currentSequences;

        //在更新成功之前，一直重新读取currentSequences，扩充为添加所有sequence之后的updatedSequences
        do
        {
            currentSequences = updater.get(holder);
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length);
            cursorSequence = cursor.getCursor();

            int index = currentSequences.length;
            //将新的sequences的值设置为cursorSequence
            for (Sequence sequence : sequencesToAdd)
            {
                sequence.set(cursorSequence);
                updatedSequences[index++] = sequence;
            }
        }
        while (!updater.compareAndSet(holder, currentSequences, updatedSequences));

        cursorSequence = cursor.getCursor();
        for (Sequence sequence : sequencesToAdd)
        {
            sequence.set(cursorSequence);
        }
    }

    /**
    * 原子移除某个指定的sequence
    * 
    * @param holder 原子更新的域所属的类对象
    * @param sequenceUpdater 原子更新的域对象
    * @param sequence 要移除的sequence
    * @param <T>
    * @return
    */
    static <T> boolean removeSequence(
        final T holder,
        final AtomicReferenceFieldUpdater<T, Sequence[]> sequenceUpdater,
        final Sequence sequence)
    {
        int numToRemove;
        Sequence[] oldSequences;
        Sequence[] newSequences;

        do
        {
            oldSequences = sequenceUpdater.get(holder);

            numToRemove = countMatching(oldSequences, sequence);

            if (0 == numToRemove)
            {
                break;
            }

            final int oldSize = oldSequences.length;
            newSequences = new Sequence[oldSize - numToRemove];

            for (int i = 0, pos = 0; i < oldSize; i++)
            {
                final Sequence testSequence = oldSequences[i];
                if (sequence != testSequence)
                {
                    newSequences[pos++] = testSequence;
                }
            }
        }
        while (!sequenceUpdater.compareAndSet(holder, oldSequences, newSequences));

        return numToRemove != 0;
    }

    private static <T> int countMatching(T[] values, final T toMatch)
    {
        int numToRemove = 0;
        for (T value : values)
        {
            if (value == toMatch) // Specifically uses identity
            {
                numToRemove++;
            }
        }
        return numToRemove;
    }
}
