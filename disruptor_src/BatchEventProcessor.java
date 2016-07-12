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

import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    //表示当前事件处理器的运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    //异常处理器
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();
    //数据提供者。(RingBuffer) 
    private final DataProvider<T> dataProvider;
    //序列栅栏
    private final SequenceBarrier sequenceBarrier;
    //真正处理事件的回调接口。 
    private final EventHandler<? super T> eventHandler;
    //事件处理器使用的序列。 
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    //超时处理器
    private final TimeoutHandler timeoutHandler;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        timeoutHandler = (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(false);//设置运行状态为false
        sequenceBarrier.alert();//通知序列栅栏
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        //状态设置与检测
        if (!running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Thread is already running");
        }
        //先清除序列栅栏的通知状态
        sequenceBarrier.clearAlert();

        //如果eventHandler实现了LifecycleAware，这里会对其进行一个启动通知。
        notifyStart();

        T event = null;
        //获取要申请的序列值
        long nextSequence = sequence.get() + 1L;
        try
        {
            while (true)
            {
                try
                {
                    //通过序列栅栏来等待可用的序列值
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);

                    //得到可用的序列值后，批量处理nextSequence到availableSequence之间的事件。
                    while (nextSequence <= availableSequence)
                    {
                        //获取事件
                        event = dataProvider.get(nextSequence);
                        //将事件交给eventHandler处理。
                        eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                        nextSequence++;
                    }

                    //处理完毕后，设置当前处理完成的最后序列值。
                    sequence.set(availableSequence);
                    //继续循环
                }
                catch (final TimeoutException e)
                {
                    //如果发生超时，通知一下超时处理器(如果eventHandler同时实现了timeoutHandler，会将其设置为当前的超时处理器)
                    notifyTimeout(sequence.get());
                }
                catch (final AlertException ex)
                {
                    //如果捕获了序列栅栏变更通知，并且当前事件处理器停止了，那么退出主循环。
                    if (!running.get())
                    {
                        break;
                    }
                }
                catch (final Throwable ex)
                {
                    //其他的异常都交给异常处理器进行处理。
                    exceptionHandler.handleEventException(ex, nextSequence, event);
                    //处理异常后仍然会设置当前处理的最后的序列值，然后继续处理其他事件。
                    sequence.set(nextSequence);
                    nextSequence++;
                }
            }
        }
        finally
        {
            //主循环退出后，如果eventHandler实现了LifecycleAware，这里会对其进行一个停止通知。 
            notifyShutdown();
            //设置事件处理器运行状态为停止
            running.set(false);
        }
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}