/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package com.doggy.aqs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;

import sun.misc.Unsafe;

/**
 * 提供一个依赖于FIFO等待队列的框架来实现锁和相关的同步器(semaphores, events, 等)
 * 这个类对于大多数依赖于一个int类型的state变量来维护同步状态的同步器是有效的。
 * 子类必须重写protected方法并改变这个同步state，并根据acquire操作或者release操作来定义该state的含义。
 * AQS类的其他方法会维护入等待队列等其他操作。子类可以维护其他的状态域，但只有通过{@link #getState}, {@link #setState}
 * and {@link #compareAndSetState}这三个方法改变变量才能被内部的同步状态追踪到。
 *
 * AQS的子类必须被定义为内部非共有的辅助类，如ReentrantLock#FairSync/NonFairSync
 *
 * AQS支持排他模式和共享模式。但通过排他模式进行acquire操作时，最多只能有一个线程成功acquire.
 * 通过共享模式acquire时，可能有多个线程同时acquire成功。AQS内部对于这两种模式基本没有区别，
 * 除了内部机制保证当通过共享模式acquire成功之后，必须检测等待队列中的其他线程是否能够acquire成功（排他模式则不需要检查）。
 * 两种模式会共用一个等待队列。一般来说一个同步器只会支持这两种模式中的其中一种模式的操作，
 * 但这两种模式实际上是可以一起使用的，如ReadWriteLock就混用了这两种模式。
 *
 * AQS内部还定义了一个{@link ConditionObject}类。这个类主要用于锁的同步等待。该类只能用于排他模式，
 * 需要使用该类时必须重写AQS#isHeldExclusively来表示当前的同步状态是否被当前线程独占访问。
 *
 * AQS还提供了一些方法去监测性能等。
 *
 *
 * 使用方式：
 *
 * 将该类作为基本的同步器使用，只需要重写以下方法。并通过{@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}来修改内部的同步状态。
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * 以上的每个方法的默认实现都会抛出{@link UnsupportedOperationException}。
 * 这些方法的子类实现都必须是内部线程安全的，而且方法要尽量短且非阻塞。
 * 重写这些方法是使用该类唯一的正确的方式。
 *
 * 虽然这个类的实现基于内部的一个FIFO等待队列，但是acquire操作并不要求是FIFO的。
 * 对于排他模式的acquire/release，其基本形式如下：
 *
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 *
 * 因为tryAcquire的检测是在将线程加入等待队列前调用的，所以一个新的acquire操作可能先于队列中的线程成功acquire.
 * 当然，如果你想实现公平锁，那么也可以在tryAcquire内部去检测等待队列是否为空，
 * 如果非空那么直接acquire失败，那么就是严格的FIFO acquire了。
 *
 * 用例：
 *
 * 这里提供一个不可重入的锁实现，支持条件等待，内部同步状态为0时表示该锁未被获取，为1时表示该锁已被获取。
 *
 *  {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     // 在条件等待时被调用。
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * 这个是一个简单的类似于只需要countDown一次的CountDownLatch实现。
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * 创建一个默认实例，state == 0.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * 等待队列的节点类。
     *
     * 该等待队列是一个CLH队列的变种实现。CLH队列一般是作为自旋锁的实现队列，然而在这里我们用它来实现同步阻塞器，
     * 我们保留了CLH队列的一些基础策略，如在其前驱节点中维护相关的控制信息。节点的status状态控制了其后继节点是否需要阻塞。
     * 当一个节点释放锁时会唤醒其后继节点。节点的status状态不控制当前线程是否能够获取锁（由AQS#state+子类语义控制）
     * 当一个线程处在队列的首节点时，会尝试去获得锁，但此时仅仅提供了一个竞争的机会，不保证一定能成功（非公平锁），如果竞争失败会重新阻塞。
     *
     * CLH队列的enqueue操作通过CAS改变volatile tail引用实现，dequeue操作则简单设置volatile head即可。
     * 需要注意的是，CLH队列的head永远指向正在持有锁或刚释放锁的节点。
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * prev引用（原生的CLH lock没有该引用）主要是用于处理一个线程被取消的事件。
     * 当一个节点被取消时，只需要将其后继的prev引用指向其前驱即可移除该被取消的节点。
     * 通过http://www.cs.rochester.edu/u/scott/synchronization/可以查看与该实现相关的自旋锁机制。
     *
     * 节点中还有一个next引用，通过该引用一个节点可以快速地找到其后继节点并唤醒他。由于没有很好的原子操作可以同时设置prev和next.
     * 所以在这里我们仅仅将next作为一个优化手段，它可能遇到竞争，包括一个新节点已经插入队列（设置好了tail + prev）,但next还没有设置。
     * 或者一个节点被取消之后，已经被移除出队列，但该取消节点的前驱节点的next还指向该取消节点。
     * 所以在一个节点的next为null或next.status为CANCELLED时，我们不能够清楚地判断next节点的状态，此时需要从tail反向遍历（通过prev）来准确判断next的值。
     *
     * 节点的取消引入了一些保守策略，由于我们一个队列上的任意节点都可能取消，所以我们会在一个节点取消后触发unParkSuccessor操作，
     * 该操作会移除该节点后的所有取消节点并尝试唤醒一个正常节点。unParkSuccessor操作仅在我们确定一个前驱节点会执行这个责任时，才可以省略。
     *
     * CLH队列需要一个dummy节点进行初始化，head和tail初始化都指向该节点。但我们并不一开始创建AQS队列时就初始化，而是延迟到第一个enq操作再初始化。
     *
     * 在ConditionObject中的等待节点也是用的这个类，但用的是nextWaiter进行节点连接,由于改变该引用的操作都在独占锁内部，
     * 所以是线程安全的，只要简单设置即可（该引用不需要是volatile）。
     * await时，会往Condition queue中插入一个等待节点，其节点状态为CONDITION.signal操作则会将Condition queue的第一个节点移入同步队列并标识为status=0.
     * 这些操作都是在排他锁内操作的，所以是线程安全的。
     */
    static final class Node {
        /**
         * nextWaiter会初始化为以下两个值中的其中一个，
         * 当为排他模式（即支持ConditionObject）时会设置为SHARED
         * 当为共享模式时会设置为EXCLUSIVE.
         */
        /** 标记实例，标识当前节点为共享模式 */
        static final Node SHARED = new Node();
        /** 标记一个节点是处于排他模式*/
        static final Node EXCLUSIVE = null;

        /** 用于表示当前节点（线程）已取消*/
        static final int CANCELLED =  1;
        /** 表示需要unpark当前节点的后继节点 */
        static final int SIGNAL    = -1;
        /** 表示当前节点在Condition queue中 */
        static final int CONDITION = -2;
        // TODO
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        static final int PROPAGATE = -3;

        /**
         *   waitStatus的取值只可能是以下几个
         *   SIGNAL:     表明当前节点的后继节点已阻塞或者很快就会进入阻塞状态，
         *               所以当前节点在release或者cancel时必须要unpark其后继节点。
         *               为了避免竞争导致线程不被唤醒[前一个节点已经release完成，其后继再将其标记为SIGNAL]，
         *               一个acquire操作是这样的，
         *               tryAcquire failed -> atomic set prev.waitStatus to SIGNAL
         *               -> tryAcquire failed again -> blocked.
         *   CANCELLED:  由于超时或者中断（interrupt）,当前节点已被取消。
         *               一个节点进入该状态后就再也不会离开这个状态,一个节点只能自己把状态设为CANCELLED，其他节点不能设置该状态。
         *   CONDITION:  当前节点在一个等待队列（Condition queue）中。
         *               它不会被用作同步队列的节点，除非其被转入到同步队列中（signal/signalAll）,
         *               当被转入到同步队列时，waitStatus重置为0
         *   // TODO
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *   0:          初始化状态，一个节点刚被加入同步队列时的状态，一个节点刚从Condition queue转移到同步队列时也会从CONDITION 变为0
         *
         *   由上可知非负值（0 / CANCELLED）表示当前节点释放时不需要unpark其后继节点。
         *
         *   waitStatus在同步队列中被初始化为0， 在条件队列（Condition queue）中被初始化为CONDITION
         *   通过CAS / volatile write 可以修改该值。
         */
        volatile int waitStatus;

        /**
         * 指向其前驱节点的引用，当前驱节点的状态为CANCELLED时，
         * 会移除相关前驱节点直到遇到一个非CANCELLED节点，
         * 由于head指向的节点状态不会为CANCELLED,所以一定能遇到一个非CANCELLED节点。
         */
        volatile Node prev;

        /**
         * 指向其后继节点的引用，由于存在数据争用，
         * 所以当一个节点的next为null时不表示该节点就是tail节点，而是需要通过tail来反向遍历确定真正的next节点。
         * 我们将一个CANCELLED节点的next指针指向自身而不是设置为null, 这样方便了同步队列的处理。
         */
        volatile Node next;

        /**
         * 当前节点持有的线程
         */
        volatile Thread thread;

        /**
         * 指向条件队列中的下一个节点，当共享模式时该值为Node#SHARED.
         * 由于该值的读写操作都是在锁内的，所以自然是线程安全的，所以该值不需要是volatile类型。
         */
        Node nextWaiter;

        /**
         * 如果一个节点是共享模式节点则返回true否则返回false
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * 返回其前驱节点，当其前驱节点为null时抛出NPE.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * 同步队列的头节点，除了初始化dummy,则只能通过setHead()改变指向。
     * 如果一个head非空，那么其保证指向的节点的状态不为CANCELLED.当head指向dummy节点时满足要求，
     * 否则一个节点只有在tryAcquire成功之后才会去设置head指向其自身，
     * 而一个CANCELLED节点不可能acquire成功，一个acquire成功的节点也不会把自己的状态设为CANCELLED.
     */
    private transient volatile Node head;

    /**
     * 同步队列的尾节点，除了初始化dummy，该引用只在enq时改变。
     */
    private transient volatile Node tail;

    /**
     * 同步状态
     */
    private volatile int state;

    /**
     * 返回内部的当前同步状态，该操作有 volatile read 内存语义。
     */
    protected final int getState() {
        return state;
    }

    /**
     * 原子设置内部的同步状态，该操作有 volatile write 内存语义。
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * CAS原子设置内部的同步状态，该操作有 volatile read 以及 volatile write 内存语义。
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * 该值用于提供一个粗糙的优化策略。当需要挂起的时长t大于该值时，使用LockSupport#parkNanos(t)
     * 阻塞挂起线程，当t小于该值时则认为挂起的线程所带来的线程切换代价大于自旋等待的代价，所以会直接自旋。
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * 往同步队列中插入一个等待节点，必要时执行dummy节点初始化。
     * @param node the node to insert
     * @return node's predecessor
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            // 当tail节点为null时表示同步队列未初始化，此时执行初始化将head与tail节点均指向dummy节点。
            if (t == null) {
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                node.prev = t;
                // CAS 设置tail节点，一旦tail设置成功则表示成功加入到队列了。
                if (compareAndSetTail(t, node)) {
                    // 设置next节点，由于此处存在着争用。
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * 通过当前线程创建一个等待节点并将其加入到等待队列中。
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        // 提供一个快速路径插入队列，如果失败会使用enq()进行全路径插入。
        Node pred = tail;
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        enq(node);
        return node;
    }

    /**
     * 将node设置为同步队列的首节点，该方法只会在tryAcquire成功之后调用，也就是说调用该方法的node的waitStatus不能为CANCELLED.
     * 该操作同时会清除节点的prev引用以帮助GC.
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * 在排他模式下执行acquire[即acquire成功后不需要尝试让后继节点也尝试acquire].
     * 该acquire期间如果发现线程中断则只是简单的设置线程中断标志而不抛出异常。
     * 该方法常用于Lock#lock方法。
     *
     * @param arg the acquire argument.  该值将直接传递给tryAcquire(),具体的
     *            意义与实际同步器相关。
     */
    public final void acquire(int arg) {
        // 先调用tryAcquire再尝试入同步队列，所以可以实现非公平锁。
        if (!tryAcquire(arg) &&
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }


    /**
     * 尝试获得锁、挂起线程等操作。
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                // 如果当前节点的前驱节点为head 且 尝试获得锁成功则设置head并返回当前线程的中断状态
                if (p == head && tryAcquire(arg)) {
                    // setHead时会把当前节点绑定的线程置为null
                    setHead(node);
                    // 帮助GC
                    p.next = null;
                    failed = false;
                    return interrupted;
                }
                // 检测、标记前驱节点的状态，可能的话挂起当前线程并检测线程的中断状态。
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            // 只有tryAcquire抛出异常时，该代码段会执行。
            // acquire失败则将该节点标记为CANCELLED并尝试唤醒successor
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 检测一个节点在tryAcquire失败后是否需要挂起（即前驱节点的ws是否为SIGNAL）.
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        if (ws == Node.SIGNAL)
            /*
             * 在tryAcquire失败之后，检测到其前驱节点已经设置为SIGNAL,这表示我们可以安全地park当前线程了。
             * 当prev节点release之后，其会unpark()当前节点。
             */
            return true;
        if (ws > 0) {
            /*
             * 如果前驱节点为CANCELLED状态则重建同步队列，移除这些取消的节点。
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /*
             * 此时前驱节点的状态只可能是0或者PROPAGATE.在这里我们通过CAS尝试将其设置为SIGNAL,
             * 但我们不会马上认为该节点可以park了，该方法调用者需要再次检测是否能够acquire成功，
             * 以避免我们将一个已经release的节点标记为SIGNAL,导致当前节点一直无法往下获得锁。
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * 可中断的acquire方法，在acquire过程中检测到中断会抛出InterruptedException.
     * 与acquire方法的不同在于1.先检测线程是否中断 2. 使用doAcquireInterruptibly 代替acquireQueued().
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * 与acquireQueued唯一的不同在于直接抛出异常而不是给线程标记中断。
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null;
                    // help GC
                    failed = false;
                    return;
                }
                // 与acquireQueued唯一的不同在于会抛出异常。
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    // 中断之后直接抛出异常
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }
    
    /**
     *  尝试在nanosTimeout时间长度内获得排他锁，获取成功返回success,否则返回false.
     *  如果过程中线程被中断，则抛出InterruptedException
     *  该方法用于{@link Lock#tryLock(long, TimeUnit)}
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
        throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
            doAcquireNanos(arg, nanosTimeout);
    }
    
    /**
     * 超时排他的acquire实现
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
        throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        // 设置最后时间
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        // 如果该线程获取失败要调用CANCELLED标记为
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    // 获得锁成功后设置head并解除当前节点与线程的绑定关系。
                    setHead(node);
                    // help GC
                    p.next = null;
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                // 这里当剩余的timeout时间大于spinForTimeoutThreshold则直接block,否则自旋等待。
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                // 感知线程是否被中断
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 取消一个节点的acquire、从同步队列中移除该同步节点并尝试唤醒其后继节点。
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        node.thread = null;

        // 向前找到一个不为CANCELLED的前驱节点
        Node pred = node.prev;
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        // 后续的所有compareAndSetNext操作都可能失败，一旦失败表示当前节点已被其他的cancel操作或者signal操作移出队列
        // 那么就不需要其他动作了。
        Node predNext = pred.next;

        // 这里可以简单用一个volatile write。
        // 在这步之后，当前节点就被标记为CANCELLED,则其他节点可以跳过当前节点。
        // 在这之前不受其他线程影响。
        node.waitStatus = Node.CANCELLED;

        // 如果我们在tail则表示没有需要唤醒的节点，此时直接移除同步队列即可。
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            int ws;
            // 1. pred != head只是一个预判断，当不满足该条件时则可以考虑直接唤醒
            // 2. (ws = pred.waitStatus) == Node.SIGNAL || （ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))
            // 该判断会尝试将pred节点的状态设为SIGNAL
            // 3. pred.next != null 该条件则表明了pred节点的setHead还没调用成功（setHead在tryAcquire成功后release之前调用），
            // 所以之前设置的SIGNAL标志是有效的，此时可以简单地移除CANCELLED节点而不需要唤醒其后继节点。
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                // 如果其next节点不是一个CANCELLED节点，则将前一个节点的next指向该节点。
                // 否则可以直接不管，等待前驱节点的signal来移除当前节点。
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                unparkSuccessor(node);
            }

            // help GC
            node.next = node;
        }
    }
    
    /**
     *  执行在排他模式下的释放操作。
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = head;
            // 如果释放成功后，检测到当前线程的状态为SIGNAL,则需要唤醒后继节点。
            // 这也是为什么shouldParkAfterFailedAcquire方法要求在设置SIGNAL成功后，
            // 需要再次tryAcquire，这样方能保证happen-before关系
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * 如果有有效(状态为PROPAGATE/SIGNAL)的后继的节点则唤醒该节点。
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
        /*
         * 如果当前节点的waitStatus不为0或者CANCELLED,那么可以尝试清除状态
         * 失败了也没关系。
         */
        int ws = node.waitStatus;
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        /*
         * 尝试查找有效的successor,此时只要查找当前时刻的有效后继节点即可，后续竞态插入的节点，
         * 会自己来保证自己会被唤醒(set signal success 之后再次调用tryAcquire检查)。
         */
        Node s = node.next;
        // 当next为一个无效节点时，则通过tail反向遍历来查找其后继节点。
        // 如果在查找到一个有效节点s之后，s将自己标记为CANCELLED,那也没关系，
        // 因为s在将自己设置为CANCELLED之后会尝试唤醒后继节点，所以不会有活性问题。
        if (s == null || s.waitStatus > 0) {
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null)
            LockSupport.unpark(s.thread);
    }
    
    /**
     *  该类主要用于实现排他模式下的条件等待，即Lock的condition wait,其内部是一个Condition queue,队列节点与AQS使用同一个类。
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        // 该队列里的大部分操作都不需要volatile,因为该队列对应的操作大部分在锁内进行，状态是天然同步的。
        /** 条件队列的首节点 */
        private transient Node firstWaiter;
        /** 条件队列的尾节点 */
        private transient Node lastWaiter;
        
        public ConditionObject() { }
    
        /**
         * 不可中断的条件等待队列
         *
         * 保存当前锁的state值
         * 会阻塞直到signal操作
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            // 在fullRelease之前的动作在锁内执行
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            // 当该节点没有被移入到同步队列之前都是await状态
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            // 尝试获得锁
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }
    
        /**
         * 可中断的条件等待
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                // 检测中断标志
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            /*
             * 这里可能抛出IllegalMonitorStateException异常，此时会直接进入到外部代码块
             * 外部代码块一般是
             * condition = lock.newCondition()
             * lock.lock()
             * try{
             *      while(condition not meet){
             *          // maybe throw Exception here.
             *          condition.await();
             *      }
             * }finally{
             *     // 由于condition await在抛出异常时可能没有持有锁（tryAcquire失败导致异常），所以在这种情况下的unlock必须失败！！！
             *     // 实际实现上，一般在实现有Condition wait功能的锁中，tryAcquire都会使用isHeldExclusive来判断是否能够释放锁！！！
             *     lock.unlock();
             * }
             */
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            // 能够走到这里表示acquireQueued并没有抛出异常，所以acquireQueue中tryAcquire必然是成功的，所以走到这里就重新持有锁了,所以可以简单执行后续操作。
            // 由signal唤醒的节点，在enqueue时会将nextWaiter设置为null
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    // 在signal前timeout则表示超时，否则实际上没有timeout.由于时间粒度不是nanotimes导致需要此判断
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    // 精确的nanoTime,所以不需要比较signal时间
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }
        
        
        // Internal methods
        /**
         * 添加一个节点，该方法永远在锁内调用，所以线程安全。
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                firstWaiter = node;
            else
                t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }
    
        /**
         * 移除整个队列上状态为CANCELLED的节点，该方法只有在持有锁时调用。
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            // 表示下一个非CANCELLED节点
            Node trail = null;
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    // 最终将firstWaiter定位到第一个非CANCELLED节点
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }

        /**
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signal() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }
        
        /**
         * 将一个非CANCELLED节点从等待队列移到同步队列并设置其nextWaiter为null.
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
                // transferForSignal可能会遇到因为timeout/interrupted取消的节点，此时应该跳过该节点
            } while (!transferForSignal(first) &&
                (first = firstWaiter) != null);
        }
        /**
         * 唤醒所有节点，实际上会将所有节点移到同步队列
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }
        
        /**
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }
        
        /** Mode meaning to reinterrupt on exit from wait */
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        private static final int THROW_IE    = -1;
        
        /**
         * 检测中断状态
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                0;
        }
        
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }
        

        //  support for instrumentation
        
        /**
         * 判断该条件等待队列是否由某个AQS创建
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }
        
        /**
         * 判断该等待队列内是否还有其他节点在等待，只在当前线程持有锁时进行。
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }
        
        /**
         * 返回等待节点个数，只在持有锁的情况下调用。
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }
        
        /**
         * 返回等待线程集合，只在持有锁的情况下执行。
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }
    
    /**
     *  判断当前线程是否在排他模式下持有锁，该方法只在ConditionObject中使用，
     *  所以对于不需要Condition wait功能的锁可以不重写该方法。
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    unparkSuccessor(h);
                }
                else if (ws == 0 &&
                         !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * 设置当前同步队列的head,然后检测其后继节点是否在共享模式下等待，如果是则尝试唤醒其后继。
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            // 后继节点是一个未知节点或者是一个共享节点则尝试唤醒。
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * 由于使用了Thread#interrupted(),所以该方法会清空线程的中断状态，
     * 所以调用者必须正确地处理线程中断的情况。
     * @return true表示线程已中断 false表示未中断
     */
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();
    }
    
    /*************************  SHARED MODE ***************************/
    
    /**
     *  共享模式下获得锁，不可中断。
     *  与排他模式最大的不同在于，在获得锁成功后，后唤醒其后继节点。
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            doAcquireShared(arg);
    }
    
    /**
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        // 与排他模式最大的不同在于，在获得锁成功后，会尝试让其后继节点也试图获得锁
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
            ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        return findNodeFromTail(node);
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * 将一个节点从等待队列移入同步队列
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /*
         * 无法将一个节点从CONDITION变为0唯一的可能是该节点由于超时或者interrupt使得节点状态为CANCELLED
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        Node p = enq(node);
        int ws = p.waitStatus;
        // 此时持有锁，所以可以简单将其前面的节点设置为signal.
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * 当中断发生时判断，该中断是在其他节点signal该节点之前还是之后，如果是之前，则抛出IE,否则简单设置中断标志。
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        // 设置成功表示中断在其他节点signal当前节点之前发生，所以await应该要IE.
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /*
         * 发生这个情况只有一个原因，就是其他节点signal了当前节点，此时发生中断则只应该简单设置中断标志。
         * 在这种情况下我们只需要等待signal动作的enq完成即可，由于该条件的出现比较少见，所以简单自旋。
         */
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
