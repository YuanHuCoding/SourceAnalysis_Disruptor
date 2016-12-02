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
 * Coordinates claiming sequences for access to a data structure while tracking dependent {@link Sequence}s
 **Sequencer接口，扩展了Cursored和Sequenced接口。在前两者的基础上，增加了消费与生产相关的方法。其中一个比较重要的设计是关于**GatingSequence的设计
 Sequencer 是 Disruptor 的真正核心。此接口有两个实现类 SingleProducerSequencer、MultiProducerSequencer ，它们定义在生产者和消费者之间快速、正确地传递数据的并发算法。
 */
public interface Sequencer extends Cursored, Sequenced
{
    /**
     * Set to -1 as sequence starting point
     * 序列初始值 -1
     */
    long INITIAL_CURSOR_VALUE = -1L;

    /**
     * Claim a specific sequence.  Only used if initialising the ring buffer to
     * a specific value.
     * 声明一个序列，这个方法在初始化RingBuffer的时候被调用。
     * 
     * @param sequence The sequence to initialise too.
     */
    void claim(long sequence);

    /**
     * Confirms if a sequence is published and the event is available for use; non-blocking.
     * 判断一个序列是否被发布，并且发布到序列上的事件是可处理的。非阻塞方法。
     *
     * @param sequence of the buffer to check
     * @return true if the sequence is available for use, false if not
     */
    boolean isAvailable(long sequence);

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     * 添加一些追踪序列到当前实例，添加过程是原子的。 
     * 这些控制序列一般是其他组件的序列，当前实例可以通过这些序列来查看其他组件的序列使用情况。 
     *
     * @param gatingSequences The sequences to add.
     */
    void addGatingSequences(Sequence... gatingSequences);

    /**
     * Remove the specified sequence from this sequencer.
     * 移除控制序列。 
     *
     * @param sequence to be removed.
     * @return <tt>true</tt> if this sequence was found, <tt>false</tt> otherwise.
     */
    boolean removeGatingSequence(Sequence sequence);

    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     基于给定的追踪序列创建一个序列栅栏，这个栅栏是提供给事件处理者在判断Ringbuffer上某个事件是否能处理时使用的。

     SequenceBarrier我们之后会详讲，这里我们可以理解为用来协调消费者消费的对象。例如消费者A依赖于消费者B，就是消费者A一定要后于消费者B消费，
     也就是A只能消费B消费过的，也就是A的sequence一定要小于B的。这个Sequence的协调，通过A和B设置在同一个SequenceBarrier上实现。
     同时，我们还要保证所有的消费者只能消费被Publish过的。

     SequenceBarrier用来在消费者之间以及消费者和RingBuffer之间建立依赖关系。在Disruptor中，依赖关系实际上指的是Sequence的大小关系，
     消费者A依赖于消费者B指的是消费者A的Sequence一定要小于等于消费者B的Sequence，这种大小关系决定了处理某个消息的先后顺序。
     因为所有消费者都依赖于RingBuffer，所以消费者的Sequence一定小于等于RingBuffer中名为cursor的Sequence，
     即消息一定是先被生产者放到Ringbuffer中，然后才能被消费者处理。 SequenceBarrier在初始化的时候会收集需要依赖的组件的Sequence，
     RingBuffer的cursor会被自动的加入其中。需要依赖其他消费者和/或RingBuffer的消费者在消费下一个消息时，
     会先等待在SequenceBarrier上，直到所有被依赖的消费者和RingBuffer的Sequence大于等于这个消费者的Sequence。
     当被依赖的消费者或RingBuffer的Sequence有变化时，会通知SequenceBarrier唤醒等待在它上面的消费者。
     *
     * @param sequencesToTrack
     * @return A sequence barrier that will track the specified sequences.
     * @see SequenceBarrier
     */
    SequenceBarrier newBarrier(Sequence... sequencesToTrack);

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     * 获取控制序列里面当前最小的序列值。
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    long getMinimumSequence();

    /**
     * Get the highest sequence number that can be safely read from the ring buffer.  Depending
     * on the implementation of the Sequencer this call may need to scan a number of values
     * in the Sequencer.  The scan will range from nextSequence to availableSequence.  If
     * there are no available values <code>&gt;= nextSequence</code> the return value will be
     * <code>nextSequence - 1</code>.  To work correctly a consumer should pass a value that
     * it 1 higher than the last sequence that was successfully processed.
     *
     * 获取RingBuffer上安全使用的最大的序列值。 
     * 具体实现里面，这个调用可能需要序列上从nextSequence到availableSequence之间的值。 
     * 如果没有比nextSequence大的可用序列，会返回nextSequence - 1。 
     * 为了保证正确，事件处理者应该传递一个比最后的序列值大1个单位的序列来处理。
     *
     * @param nextSequence      The sequence to start scanning from.
     * @param availableSequence The sequence to scan to.
     * @return The highest value that can be safely read, will be at least <code>nextSequence - 1</code>.
     */
    long getHighestPublishedSequence(long nextSequence, long availableSequence);

    /* 
     * 通过给定的数据提供者和控制序列来创建一个EventPoller
     */ 
    <T> EventPoller<T> newPoller(DataProvider<T> provider, Sequence... gatingSequences);
}