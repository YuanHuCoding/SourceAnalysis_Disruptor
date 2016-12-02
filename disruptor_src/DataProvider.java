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

//按序列值来获取数据
/*
这个方法就是获取某个sequence对应的对象，对象类型在这里是抽象的（T）。
这个方法对于RingBuffer会在两个地方调用，第一个是在生产时，这个Event对象需要被生产者获取往里面填充数据。
第二个是在消费时，获取这个Event对象用于消费。 
*/
public interface DataProvider<T>
{
    T get(long sequence);
}
