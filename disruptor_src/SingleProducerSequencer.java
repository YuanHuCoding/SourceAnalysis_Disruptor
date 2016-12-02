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

import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.util.Util;

/**
 * 左边缓存行填充
 */
abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    public SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

/**
 * 真正需要关心的数据.
 */
abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    public SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    // nextValue表示生产者下一个可以使用的位置的序号,一开始是-1.
    protected long nextValue = Sequence.INITIAL_VALUE;

    // cachedValue表示上一次消费者消费数据时的位置序号,一开始是-1.
    protected long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 * <p>
 * <p>Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.
 */
/**
 * 用于单生产者模式场景, 保存/追踪生产者和消费者的位置序号。
   由于这个类并没有实现任何的Barrier，所以在Disruptor框架中，这个类并不是线程安全的。
   不过由于从命名上看，就是单一生产者，所以在使用的时候也不会用多线程去调用里面的方法。 
 */
public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    // 右边缓存行填充数据.
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    /**
     * 使用给定的bufferSize和waitStrategy创建实例.
     */
    public SingleProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    /**
     * 判断RingBuffer是否还有可用的空间能够容纳requiredCapacity个Event.
      hasAvailableCapacity方法可以这样理解：
      当前序列的nextValue + requiredCapacity是生产者要申请的序列值。
      当前序列的cachedValue记录的是之前消费者申请的序列值。    
      想一下一个环形队列，生产者在什么情况下才能申请一个序列呢？
      生产者当前的位置在消费者前面，并且不能从消费者后面追上生产者(因为是环形)，
      即 生产者要申请的序列值大于消费者之前的序列值 且 生产者要申请的序列值减去环的长度要小于消费者的序列值 
      如果满足这个条件，即使不知道当前消费者的序列值，也能确保生产者可以申请给定的序列。
      如果不满足这个条件，就需要查看一下当前消费者的最小的序列值(因为可能有多个消费者)，
      如果当前要申请的序列值比当前消费者的最小序列值大了一圈(从后面追上了)，那就不能申请了(申请的话会覆盖没被消费的事件)，
      也就是说没有可用的空间(用来发布事件)了，也就是hasAvailableCapacity方法要表达的意思。
      */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        // 生产者下一个可使用的位置序号
        long nextValue = this.nextValue;

        // 从nextValue位置开始,如果再申请requiredCapacity个位置,将要达到的位置,因为是环形数组,所以减去bufferSize。
        // 下一位置加上所需容量减去整个bufferSize，如果为正数，那证明至少转了一圈，则需要检查gatingSequences（由消费者更新里面的Sequence值）以保证不覆盖还未被消费的.
        // 下面会用该值和消费者的位置序号比较.
        // 重叠点位置
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;

        // 消费者上一次消费的位置, 消费者每次消费之后会更新该值.
        // Disruptor经常用缓存，这里缓存所有gatingSequences里最小的那个，这样不用每次都遍历一遍gatingSequences，影响效率.
        long cachedGatingSequence = this.cachedValue;

        // 先看看这个条件的对立条件: wrapPoint <= cachedGatingSequence && cachedGatingSequence <= nextValue
        // 表示当前生产者走在消费者的前面, 并且就算再申请requiredCapacity个位置达到的位置也不会覆盖消费者上一次消费的位置.
        // (就更不用关心当前消费者消费的位置了,因为消费者消费的位置是一直增大的),这种情况一定能够分配requiredCapacity个空间.

        // wrapPoint > cachedGatingSequence
        // 重叠位置大于缓存的消费者处理的序号，说明有消费者没有处理完成，不能够放置数据
        // cachedGatingSequence > nextValue
        // 只会在 https://github.com/LMAX-Exchange/disruptor/issues/76 情况下存在
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // gatingSequences保存的是消费者的当前消费位置, 因为可能有多个消费者, 所以此处获取序号最小的位置.
            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            // 顺便更新消费者上一次消费的位置...
            this.cachedValue = minSequence;
            
            // 如果申请之后的位置会覆盖消费者的位置,则不能分配空间,返回false
            if (wrapPoint > minSequence)
            {
                return false;
            }
            // 否则返回true.
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    /**
     * 申请下一个可用空间, 返回该位置的序号, 如果当前没有可用空间, 则一直阻塞直到有可用空间位置.
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    /**
     * 申请n个可用空间, 返回该位置的序号, 如果当前没有可用空间, 则一直阻塞直到有可用空间位置.
     */
    @Override
    public long next(int n)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        //复制上次成功申请的序列
        long nextValue = this.nextValue;
        //加上n后，得到本次需要申请的序列
        long nextSequence = nextValue + n;
        //本次申请的序列减去环形数组的长度，得到绕一圈后的序列
        long wrapPoint = nextSequence - bufferSize;
        //复制消费者上次消费到的序列位置
        long cachedGatingSequence = this.cachedValue;

        // 从逻辑来看，当生产者想申请某一个序列时，需要保证不会绕一圈之后，对消费者追尾；同时需要保证消费者上一次的消费最小序列没有对生产者追尾。
        // next方法是真正申请序列的方法，里面的逻辑和hasAvailableCapacity一样，只是在不能申请序列的时候会阻塞等待一下，然后重试。
        // wrapPoint > cachedGatingSequence,
        // 重叠位置大于缓存的消费者处理的序号，说明有消费者没有处理完成，不能够放置数据
        // cachedGatingSequence > nextValue
        // 只会在 https://github.com/LMAX-Exchange/disruptor/issues/76 情况下存在
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            long minSequence;
            //如果一直没有可用空间, 当前线程挂起, 不断循环检测，直到有可用空间。
            //循环判断生产者绕一圈之后，没有追上消费者的最小序列，如果还是追尾，则等待1纳秒，目前就是简单的等待，看注释是想在以后通过waitStrategy来等待
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                waitStrategy.signalAllWhenBlocking();
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?  //作者可能想以后通过waitStrategy来等待
            }

            //循环退出后，将获取的消费者最小序列，赋值给cachedValue
            this.cachedValue = minSequence;
        }

        // 将成功申请到的nextSequence赋值给nextValue
        this.nextValue = nextSequence;
        // 返回最后一个可用位置的序号.
        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    /**
     * 尝试申请一个可用空间, 如果没有,抛出异常.tryNext方法是next方法的非阻塞版本，不能申请就抛异常。 
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    /**
     * 尝试申请n个可用空间,如果没有,抛出异常.
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        // 先调用hasAvailableCapacity函数判断是否能分配, 不能直接抛出异常.
        if (!hasAvailableCapacity(n))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    /**
     * 返回当前RingBuffer的可用位置数目.
       remainingCapacity方法就是环形队列的容量减去生产者与消费者的序列差。 
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        // (多个)消费者消费的最小位置
        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        // 生产者的位置
        long produced = nextValue;
        // 空余的可用的位置数目.
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    /**
     * 更改生产者的位置序号.
       claim方法是声明一个序列，在初始化的时候用。
     */
    @Override
    public void claim(long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    /**
     * 发布sequence位置的Event.
     发布一个序列，会先设置内部游标值，然后唤醒等待的消费者。
     */
    @Override
    public void publish(long sequence)
    {
        // 首先更新生产者游标
        //cursor代表可以消费的sequence
        cursor.set(sequence);
        // 然后通知所有消费者, 数据可以被消费了.
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    /**
     * 发布这个区间内的Event.
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    /**
     * 判断sequence位置的数据是否已经发布并且可以被消费.
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
