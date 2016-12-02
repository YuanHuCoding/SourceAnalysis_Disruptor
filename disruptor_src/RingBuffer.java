/*
 * Copyright 2011 LMAX Ltd.
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


import sun.misc.Unsafe;

import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.Util;

abstract class RingBufferPad
{
    protected long p1, p2, p3, p4, p5, p6, p7;
}

/**
注释中提到对于entries数组的缓存行填充，申请的数组大小为实际需要大小加上2 * BUFFER_PAD，所占空间就是2*128字节。由于数组中的元素经常访问，所以将数组中的实际元素两边各加上128字节的padding防止false sharing。 
所以，初始化RingBuffer内所有对象时，从下标BUFFER_PAD开始，到BUFFER_PAD+bufferSize-1为止。取出某一sequence的对象，也是BUFFER_PAD开始算0,这里的：return (E) UNSAFE.getObject(entries, REF_ARRAY_BASE + ((sequence & indexMask) << REF_ELEMENT_SHIFT)); 
代表取出entries对象，地址为REF_ARRAY_BASE + ((sequence & indexMask) << REF_ELEMENT_SHIFT)的对象。这里是个对象引用，地址是以REF_ARRAY_BASE 为基址（数组基址+数组头+引用偏移），每个引用占用2^REF_ELEMENT_SHIFT个字节，sequence 对bufferSize取模乘以2^REF_ELEMENT_SHIFT。 
*/
abstract class RingBufferFields<E> extends RingBufferPad
{
    private static final int BUFFER_PAD;//数组中一共需要填充的个数
    private static final long REF_ARRAY_BASE;//数组的开始地址，这里其实是数组中真正有效数据的开始地址，是整个数组开始地址+BUFFER_PAD个引用的偏移量
    private static final int REF_ELEMENT_SHIFT;//一个引用占用的字节数的幂次方.2^n=每个数组对象引用所占空间，这个n就是REF_ELEMENT_SHIFT.
    private static final Unsafe UNSAFE = Util.getUnsafe();

    static
    {
        //获取数组中一个引用的字节数。Object数组引用长度，32位为4字节，64位为8字节
        final int scale = UNSAFE.arrayIndexScale(Object[].class);
        if (4 == scale)
        {
            REF_ELEMENT_SHIFT = 2;
        }
        else if (8 == scale)
        {
            REF_ELEMENT_SHIFT = 3;
        }
        else
        {
            throw new IllegalStateException("Unknown pointer size");
        }
        BUFFER_PAD = 128 / scale;//需要填充128字节，缓存行长度一般是128字节
        //这里需要说明下，REF_ARRAY_BASE是整个数组的起始地址+用于缓存行填充的那些空位的偏移量
        //BUFFER_PAD << REF_ELEMENT_SHIFT表示BUFFER_PAD个引用的占用字节数
        //比如一个引用占用字节数是4，那么REF_ELEMENT_SHIFT是2
        //BUFFER_PAD << REF_ELEMENT_SHIFT就相当于BUFFER_PAD * 4
        //Including the buffer pad in the array base offset
        REF_ARRAY_BASE = UNSAFE.arrayBaseOffset(Object[].class) + (BUFFER_PAD << REF_ELEMENT_SHIFT);
    }

    private final long indexMask;//数组的下表掩码
    private final Object[] entries;//用来真正存放数据的数组
    protected final int bufferSize;//数组的大小
    protected final Sequencer sequencer;//生产者序列管理者

    RingBufferFields(
        EventFactory<E> eventFactory,
        Sequencer sequencer)
    {
        this.sequencer = sequencer;
        this.bufferSize = sequencer.getBufferSize();

        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        //判断bufferSize是否是2的整数次方
        if (Integer.bitCount(bufferSize) != 1)
        {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.indexMask = bufferSize - 1;

         /**
         * 结构：缓存行填充，避免频繁访问的任一entry与另一被修改的无关变量写入同一缓存行
         * -----------------------
         * *   数组头   * BASE
         * *   Padding  * 128字节
         * * reference1 * SCALE
         * * reference2 * SCALE
         * * reference3 * SCALE
         * ..........
         * *   Padding  * 128字节
         * -----------------------
         */
         //实际定义的数组是bufferSize + 2倍的BUFFER_PAD，所以实际数组大小会偏大
        this.entries = new Object[sequencer.getBufferSize() + 2 * BUFFER_PAD];
        fill(eventFactory);//利用eventFactory初始化RingBuffer的每个槽
    }

    private void fill(EventFactory<E> eventFactory)
    {
        for (int i = 0; i < bufferSize; i++)
        {
            //看到没有，是从数组的第BUFFER_PAD+1个元素开始为有效位置，最后一个有效位置为BUFFER_PAD+bufferSize
            //在数组的前后都各有BUFFER_PAD个空位，是用来做缓存行填充用的
            entries[BUFFER_PAD + i] = eventFactory.newInstance();//队列内事件的预填充
        }
    }

    @SuppressWarnings("unchecked")
    protected final E elementAt(long sequence)
    {
        //REF_ARRAY_BASE在初始化的时候，已经被初始化为数组中第BUFFER_PAD个元素的起始地址
        return (E) UNSAFE.getObject(entries, REF_ARRAY_BASE + ((sequence & indexMask) << REF_ELEMENT_SHIFT));
    }
}

/**
 * Ring based store of reusable entries containing the data representing
 * an event being exchanged between event producer and {@link EventProcessor}s.
 *  RingBuffer的内部结构明确了：内部用数组来实现，同时有保存数组长度的域bufferSize和下标掩码indexMask，还有一个sequencer。
 *  这里要注意几点：
 *  1.整个RingBuffer内部做了大量的缓存行填充，前后各填充了56个字节，entries本身也根据引用大小进行了填充，假设引用大小为4字节，那么entries数组两侧就要个填充32个空数组位。也就是说，实际的数组长度比bufferSize要大。所以可以看到根据序列从entries中取元素的方法elementAt内部做了一些调整，不是单纯的取模。
 *  2.bufferSize必须是2的幂，indexMask就是bufferSize-1，这样取模更高效(sequence&indexMask)。
 *  3.初始化时需要传入一个EventFactory，用来做队列内事件的预填充。

 同时实现EventSequencer和EventSink代表RingBuffer是一个以Event槽为基础元素保存的数据结构
 *
 * @param <E> implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class RingBuffer<E> extends RingBufferFields<E> implements Cursored, EventSequencer<E>, EventSink<E>
{
    public static final long INITIAL_CURSOR_VALUE = Sequence.INITIAL_VALUE;
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a RingBuffer with the full option set.
     *
     * @param eventFactory to newInstance entries for filling the RingBuffer
     * @param sequencer    sequencer to handle the ordering of events moving through the RingBuffer.
     * @throws IllegalArgumentException if bufferSize is less than 1 or not a power of 2
     */
    RingBuffer(
        EventFactory<E> eventFactory,//用来初始化数组中的数据
        Sequencer sequencer)//管理生产者序列的管理者
    {
        super(eventFactory, sequencer);
    }

    /**
     * Create a new multiple producer RingBuffer with the specified wait strategy.
     *
     * @param factory      used to create the events within the ring buffer.
     * @param bufferSize   number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 or not a power of 2
     * @see MultiProducerSequencer
     */
    public static <E> RingBuffer<E> createMultiProducer(
        EventFactory<E> factory,
        int bufferSize,
        WaitStrategy waitStrategy)
    {
        MultiProducerSequencer sequencer = new MultiProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }

    /**
     * Create a new multiple producer RingBuffer using the default wait strategy  {@link BlockingWaitStrategy}.
     *
     * @param factory    used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @throws IllegalArgumentException if <tt>bufferSize</tt> is less than 1 or not a power of 2
     * @see MultiProducerSequencer
     */
    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, int bufferSize)
    {
        return createMultiProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * Create a new single producer RingBuffer with the specified wait strategy.
     *
     * @param factory      used to create the events within the ring buffer.
     * @param bufferSize   number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 or not a power of 2
     * @see SingleProducerSequencer
     */
    public static <E> RingBuffer<E> createSingleProducer(
        EventFactory<E> factory,
        int bufferSize,
        WaitStrategy waitStrategy)
    {
        SingleProducerSequencer sequencer = new SingleProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }

    /**
     * Create a new single producer RingBuffer using the default wait strategy  {@link BlockingWaitStrategy}.
     *
     * @param factory    used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @throws IllegalArgumentException if <tt>bufferSize</tt> is less than 1 or not a power of 2
     * @see MultiProducerSequencer
     */
    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize)
    {
        return createSingleProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * Create a new Ring Buffer with the specified producer type (SINGLE or MULTI)
     *
     * @param producerType producer type to use {@link ProducerType}.
     * @param factory      used to create events within the ring buffer.
     * @param bufferSize   number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 or not a power of 2
     */
    public static <E> RingBuffer<E> create(
        ProducerType producerType,
        EventFactory<E> factory,
        int bufferSize,
        WaitStrategy waitStrategy)
    {
        switch (producerType)
        {
            case SINGLE:
                return createSingleProducer(factory, bufferSize, waitStrategy);
            case MULTI:
                return createMultiProducer(factory, bufferSize, waitStrategy);
            default:
                throw new IllegalStateException(producerType.toString());
        }
    }

    /**
     * <p>Get the event for a given sequence in the RingBuffer.</p>
     * <p>
     * <p>This call has 2 uses.  Firstly use this call when publishing to a ring buffer.
     * After calling {@link RingBuffer#next()} use this call to get hold of the
     * preallocated event to fill with data before calling {@link RingBuffer#publish(long)}.</p>
     * <p>
     * <p>Secondly use this call when consuming data from the ring buffer.  After calling
     * {@link SequenceBarrier#waitFor(long)} call this method with any value greater than
     * that your current consumer sequence and less than or equal to the value returned from
     * the {@link SequenceBarrier#waitFor(long)} method.</p>
     *
     * @param sequence for the event
     * @return the event for the given sequence
     */
    @Override
    public E get(long sequence)
    {
        return elementAt(sequence);
    }

    /**
     * Increment and return the next sequence for the ring buffer.  Calls of this
     * method should ensure that they always publish the sequence afterward.  E.g.
     * <pre>
     * long sequence = ringBuffer.next();
     * try {
     *     Event e = ringBuffer.get(sequence);
     *     // Do some work with the event.
     * } finally {
     *     ringBuffer.publish(sequence);
     * }
     * </pre>
     *
     * @return The next sequence to publish to.
     * @see RingBuffer#publish(long)
     * @see RingBuffer#get(long)
     */
    @Override
    public long next()
    {
        return sequencer.next();
    }

    /**
     * The same functionality as {@link RingBuffer#next()}, but allows the caller to claim
     * the next n sequences.
     *
     * @param n number of slots to claim
     * @return sequence number of the highest slot claimed
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        return sequencer.next(n);
    }

    /**
     * <p>Increment and return the next sequence for the ring buffer.  Calls of this
     * method should ensure that they always publish the sequence afterward.  E.g.
     * <pre>
     * long sequence = ringBuffer.next();
     * try {
     *     Event e = ringBuffer.get(sequence);
     *     // Do some work with the event.
     * } finally {
     *     ringBuffer.publish(sequence);
     * }
     * </pre>
     * <p>This method will not block if there is not space available in the ring
     * buffer, instead it will throw an {@link InsufficientCapacityException}.
     *
     * @return The next sequence to publish to.
     * @throws InsufficientCapacityException if the necessary space in the ring buffer is not available
     * @see RingBuffer#publish(long)
     * @see RingBuffer#get(long)
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return sequencer.tryNext();
    }

    /**
     * The same functionality as {@link RingBuffer#tryNext()}, but allows the caller to attempt
     * to claim the next n sequences.
     *
     * @param n number of slots to claim
     * @return sequence number of the highest slot claimed
     * @throws InsufficientCapacityException if the necessary space in the ring buffer is not available
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        return sequencer.tryNext(n);
    }

    /**
     * Resets the cursor to a specific value.  This can be applied at any time, but it is worth noting
     * that it can cause a data race and should only be used in controlled circumstances.  E.g. during
     * initialisation.
     *
     * @param sequence The sequence to reset too.
     * @throws IllegalStateException If any gating sequences have already been specified.
     */
    @Deprecated
    public void resetTo(long sequence)
    {
        sequencer.claim(sequence);
        sequencer.publish(sequence);
    }

    /**
     * Sets the cursor to a specific sequence and returns the preallocated entry that is stored there.  This
     * can cause a data race and should only be done in controlled circumstances, e.g. during initialisation.
     *
     * @param sequence The sequence to claim.
     * @return The preallocated event.
     */
    public E claimAndGetPreallocated(long sequence)
    {
        sequencer.claim(sequence);
        return get(sequence);
    }

    /**
     * Determines if a particular entry has been published.
     *
     * @param sequence The sequence to identify the entry.
     * @return If the value has been published or not.
     */
    public boolean isPublished(long sequence)
    {
        return sequencer.isAvailable(sequence);
    }

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     *
     * @param gatingSequences The sequences to add.
     */
    public void addGatingSequences(Sequence... gatingSequences)
    {
        sequencer.addGatingSequences(gatingSequences);
    }

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    public long getMinimumGatingSequence()
    {
        return sequencer.getMinimumSequence();
    }

    /**
     * Remove the specified sequence from this ringBuffer.
     *
     * @param sequence to be removed.
     * @return <tt>true</tt> if this sequence was found, <tt>false</tt> otherwise.
     */
    public boolean removeGatingSequence(Sequence sequence)
    {
        return sequencer.removeGatingSequence(sequence);
    }

    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     *
     * @param sequencesToTrack the additional sequences to track
     * @return A sequence barrier that will track the specified sequences.
     * @see SequenceBarrier
     */
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack)
    {
        return sequencer.newBarrier(sequencesToTrack);
    }

    /**
     * Creates an event poller for this ring buffer gated on the supplied sequences.
     *
     * @param gatingSequences
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    public EventPoller<E> newPoller(Sequence... gatingSequences)
    {
        return sequencer.newPoller(this, gatingSequences);
    }

    /**
     * Get the current cursor value for the ring buffer.  The actual value received
     * will depend on the type of {@link Sequencer} that is being used.
     *
     * @see MultiProducerSequencer
     * @see SingleProducerSequencer
     */
    @Override
    public long getCursor()
    {
        return sequencer.getCursor();
    }

    /**
     * The size of the buffer.
     */
    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Given specified <tt>requiredCapacity</tt> determines if that amount of space
     * is available.  Note, you can not assume that if this method returns <tt>true</tt>
     * that a call to {@link RingBuffer#next()} will not block.  Especially true if this
     * ring buffer is set up to handle multiple producers.
     *
     * @param requiredCapacity The capacity to check for.
     * @return <tt>true</tt> If the specified <tt>requiredCapacity</tt> is available
     * <tt>false</tt> if now.
     */
    public boolean hasAvailableCapacity(int requiredCapacity)
    {
        return sequencer.hasAvailableCapacity(requiredCapacity);
    }


    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslator)
     */
    @Override
    public void publishEvent(EventTranslator<E> translator)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslator)
     */
    @Override
    public boolean tryPublishEvent(EventTranslator<E> translator)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorOneArg, Object)
     * com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorOneArg, A)
     */
    @Override
    public <A> void publishEvent(EventTranslatorOneArg<E, A> translator, A arg0)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorOneArg, Object)
     * com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorOneArg, A)
     */
    @Override
    public <A> boolean tryPublishEvent(EventTranslatorOneArg<E, A> translator, A arg0)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorTwoArg, Object, Object)
     * com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorTwoArg, A, B)
     */
    @Override
    public <A, B> void publishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorTwoArg, Object, Object)
     * com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorTwoArg, A, B)
     */
    @Override
    public <A, B> boolean tryPublishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorThreeArg, Object, Object, Object)
     * com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorThreeArg, A, B, C)
     */
    @Override
    public <A, B, C> void publishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1, arg2);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorThreeArg, Object, Object, Object)
     * com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorThreeArg, A, B, C)
     */
    @Override
    public <A, B, C> boolean tryPublishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1, arg2);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object...)
     */
    @Override
    public void publishEvent(EventTranslatorVararg<E> translator, Object... args)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object...)
     */
    @Override
    public boolean tryPublishEvent(EventTranslatorVararg<E> translator, Object... args)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, args);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }


    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    @Override
    public void publishEvents(EventTranslator<E>[] translators)
    {
        publishEvents(translators, 0, translators.length);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslator[], int, int)
     */
    @Override
    public void publishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize)
    {
        checkBounds(translators, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslator[])
     */
    @Override
    public boolean tryPublishEvents(EventTranslator<E>[] translators)
    {
        return tryPublishEvents(translators, 0, translators.length);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslator[], int, int)
     */
    @Override
    public boolean tryPublishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize)
    {
        checkBounds(translators, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, A[])
     */
    @Override
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0)
    {
        publishEvents(translator, 0, arg0.length, arg0);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, A[])
     */
    @Override
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0)
    {
        checkBounds(arg0, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, A[])
     */
    @Override
    public <A> boolean tryPublishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0)
    {
        return tryPublishEvents(translator, 0, arg0.length, arg0);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, A[])
     */
    @Override
    public <A> boolean tryPublishEvents(
        EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0)
    {
        checkBounds(arg0, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, A[], B[])
     */
    @Override
    public <A, B> void publishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1)
    {
        publishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, A[], B[])
     */
    @Override
    public <A, B> void publishEvents(
        EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1)
    {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, A[], B[])
     */
    @Override
    public <A, B> boolean tryPublishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1)
    {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, A[], B[])
     */
    @Override
    public <A, B> boolean tryPublishEvents(
        EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1)
    {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, A[], B[], C[])
     */
    @Override
    public <A, B, C> void publishEvents(EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2)
    {
        publishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, A[], B[], C[])
     */
    @Override
    public <A, B, C> void publishEvents(
        EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2)
    {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, A[], B[], C[])
     */
    @Override
    public <A, B, C> boolean tryPublishEvents(
        EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2)
    {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, A[], B[], C[])
     */
    @Override
    public <A, B, C> boolean tryPublishEvents(
        EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2)
    {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object[][])
     */
    @Override
    public void publishEvents(EventTranslatorVararg<E> translator, Object[]... args)
    {
        publishEvents(translator, 0, args.length, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorVararg, int, int, java.lang.Object[][])
     */
    @Override
    public void publishEvents(EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args)
    {
        checkBounds(batchStartsAt, batchSize, args);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object[][])
     */
    @Override
    public boolean tryPublishEvents(EventTranslatorVararg<E> translator, Object[]... args)
    {
        return tryPublishEvents(translator, 0, args.length, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorVararg, int, int, java.lang.Object[][])
     */
    @Override
    public boolean tryPublishEvents(
        EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args)
    {
        checkBounds(args, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Publish the specified sequence.  This action marks this particular
     * message as being available to be read.
     *
     * @param sequence the sequence to publish.
     */
    @Override
    public void publish(long sequence)
    {
        sequencer.publish(sequence);
    }

    /**
     * Publish the specified sequences.  This action marks these particular
     * messages as being available to be read.
     *
     * @param lo the lowest sequence number to be published
     * @param hi the highest sequence number to be published
     * @see Sequencer#next(int)
     */
    @Override
    public void publish(long lo, long hi)
    {
        sequencer.publish(lo, hi);
    }

    /**
     * Get the remaining capacity for this ringBuffer.
     *
     * @return The number of slots remaining.
     */
    public long remainingCapacity()
    {
        return sequencer.remainingCapacity();
    }

    private void checkBounds(final EventTranslator<E>[] translators, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(translators, batchStartsAt, batchSize);
    }

    private void checkBatchSizing(int batchStartsAt, int batchSize)
    {
        if (batchStartsAt < 0 || batchSize < 0)
        {
            throw new IllegalArgumentException("Both batchStartsAt and batchSize must be positive but got: batchStartsAt " + batchStartsAt + " and batchSize " + batchSize);
        }
        else if (batchSize > bufferSize)
        {
            throw new IllegalArgumentException("The ring buffer cannot accommodate " + batchSize + " it only has space for " + bufferSize + " entities.");
        }
    }

    private <A> void checkBounds(final A[] arg0, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
    }

    private <A, B> void checkBounds(final A[] arg0, final B[] arg1, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
    }

    private <A, B, C> void checkBounds(
        final A[] arg0, final B[] arg1, final C[] arg2, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
        batchOverRuns(arg2, batchStartsAt, batchSize);
    }

    private void checkBounds(final int batchStartsAt, final int batchSize, final Object[][] args)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(args, batchStartsAt, batchSize);
    }

    private <A> void batchOverRuns(final A[] arg0, final int batchStartsAt, final int batchSize)
    {
        if (batchStartsAt + batchSize > arg0.length)
        {
            throw new IllegalArgumentException(
                "A batchSize of: " + batchSize +
                    " with batchStatsAt of: " + batchStartsAt +
                    " will overrun the available number of arguments: " + (arg0.length - batchStartsAt));
        }
    }

    private void translateAndPublish(EventTranslator<E> translator, long sequence)
    {
        try
        {
            translator.translateTo(get(sequence), sequence);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A> void translateAndPublish(EventTranslatorOneArg<E, A> translator, long sequence, A arg0)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A, B> void translateAndPublish(EventTranslatorTwoArg<E, A, B> translator, long sequence, A arg0, B arg1)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0, arg1);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A, B, C> void translateAndPublish(
        EventTranslatorThreeArg<E, A, B, C> translator, long sequence,
        A arg0, B arg1, C arg2)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0, arg1, arg2);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublish(EventTranslatorVararg<E> translator, long sequence, Object... args)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, args);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublishBatch(
        final EventTranslator<E>[] translators, int batchStartsAt,
        final int batchSize, final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                final EventTranslator<E> translator = translators[i];
                translator.translateTo(get(sequence), sequence++);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A> void translateAndPublishBatch(
        final EventTranslatorOneArg<E, A> translator, final A[] arg0,
        int batchStartsAt, final int batchSize, final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, arg0[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B> void translateAndPublishBatch(
        final EventTranslatorTwoArg<E, A, B> translator, final A[] arg0,
        final B[] arg1, int batchStartsAt, int batchSize,
        final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B, C> void translateAndPublishBatch(
        final EventTranslatorThreeArg<E, A, B, C> translator,
        final A[] arg0, final B[] arg1, final C[] arg2, int batchStartsAt,
        final int batchSize, final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i], arg2[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private void translateAndPublishBatch(
        final EventTranslatorVararg<E> translator, int batchStartsAt,
        final int batchSize, final long finalSequence, final Object[][] args)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, args[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }
}
