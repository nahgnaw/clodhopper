package org.battelle.clodhopper.task;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

/*=====================================================================
 * 
 *                       CLODHOPPER CLUSTERING API
 * 
 * -------------------------------------------------------------------- 
 * 
 * Copyright (C) 2013 Battelle Memorial Institute 
 * http://www.battelle.org
 * 
 * -------------------------------------------------------------------- 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 * -------------------------------------------------------------------- 
 * *
 * AbstractTask.java
 *
 *===================================================================*/

/**
 * <p>The <code>AbstractTask</code> class is an abstract base implementation of 
 * <code>Task</code> on which to build extensions that perform time-consuming chores.
 * <p>Extensions of this class must implement a method <code>doTask()</code> in
 * which to perform their work.  They cannot define a
 * <code>run()</code> method, since AbstractTask's run() method is final.  This is done
 * to ensure proper handling of exceptions, event handling, and cleanup.</p>
 *
 * @author R. Scarberry
 * @since 1.0
 */
public abstract class AbstractTask<V> implements Task<V> {
	
        private static final Logger LOGGER = Logger.getLogger(AbstractTask.class);
        
        // The outcome of the AbstractTask.  Never null, but NOT_FINISHED means what
        // it sounds like.
        private TaskOutcome outcome = TaskOutcome.NOT_FINISHED;
        
        // Stores the results of error conditions.  If <code>error(String mesg)</code>
        // is called by the subclass' <code>doTask()</code> method,
        // the string is set to the message and the throwable is set to a RuntimeException
        // which is immediately thrown.
        private Throwable error;
        private String errorMsg;
        
        // The owning thread.  Only one thread may ever execute a given
        // AbstractTask<V> at a time.  This is set near the beginning of the
        // run() method.
        private volatile Thread owner;
        
        // Cancel flag set by cancel().  Attempting to post a message or
        // progress triggers a CancellationException, which interrupts the
        // run method.
        private AtomicBoolean cancelFlag = new AtomicBoolean();
        // Set to true in postBegun(), which can only be called once.
        private AtomicBoolean hasBegun = new AtomicBoolean();
        // Set to true in postEnded(), which also can only be called once.
        private AtomicBoolean hasEnded = new AtomicBoolean();
        
        // Set to true by pause;
        private AtomicBoolean pauseFlag = new AtomicBoolean();

        // Endpoints for progress reporting.  To have the expected effect,
        // these must be set before the task is started.
        private double beginProgress = 0.0;
        private double endProgress = 1.0;
        
        // The last reported progress.
        private double progress;
        
        // The result returned by get().
        private V result;
     
        // Task event support
        private TaskEventSupport eventSupport = new TaskEventSupport(this);
        
        /**
         * Add a listener to the receiver's list of listeners.  The listener
         * is normally added before the thread executing the AbstractTask is started.  As
         * the AbstractTask executes, registered listeners receive event notifications
         * when the AbstractTask starts, when it ends, messages, and progress indications.
         * (Subclasses of <code>AbstractTask</code> are responsible for messages and
         * progress, but <code>AbstractTask</code> ensures the propagation of start and
         * finish events in its run method.)
         * @param l - an object which implements <code>TaskListener</code>.
         */
        public void addTaskListener(TaskListener l) {
        	eventSupport.addTaskListener(l);
        }

        /**
         * Remove a registered listener.  Normally called after the AbstractTask is
         * finished.
         * @param l - a <code>TaskListener</code> previously added via
         * <code>addTaskListener(l)</code>.
         */
        public void removeTaskListener(TaskListener l) {
        	eventSupport.removeTaskListener(l);
        }

        /**
         * Set the begining and ending progress endpoints.  This method should be
         * called before starting the AbstractTask.  If not called, the endpoints default
         * to 0.0 and 1.0.
         * @param begin - the beginning progress.
         * @param end - the ending progress.
         */
        public void setProgressEndpoints(double begin, double end) {        
            if (begin < 0.0 || end < 0.0 || (begin > end)) {
                throw new IllegalArgumentException(
                        "invalid progress endpoints (begin == " + begin
                        + ", end == " + end + ")");
            }
            if (hasBegun.get()) {
                throw new IllegalStateException(
                        "endpoints must be set before running the AbstractTask"
                );
            } else {
                beginProgress = begin;
                endProgress = end;
            }
        }

        /**
         * Get the beginning progress.
         * @return double
         */
        public double getBeginProgress() {
            return beginProgress;
        }

        /**
         * Get the ending progress.
         * @return double
         */
        public double getEndProgress() {
            return endProgress;
        }
        
        /**
         * Returns the result of the computation, waiting if necessary until the
         * computation is finished.
         * 
         * @return the completed result, if the computation completed successfully.
         *
         * @throws CancellationException - if the computation was cancelled.
         * @throws ExecutionException - the the computation encountered an exception.
         * @throws InterruptedException - if the current thread was interrupted 
         *   while waiting.
         */
        public V get() throws InterruptedException, ExecutionException {
            while(!hasEnded.get()) {
                synchronized (this) {
                   wait();
                }
            }
            return getResultAfterHasEnded();
        }
        
        /**
         * Returns the result of the computation, waiting if necessary for at most
         * the specified amount of time for the computation to finish.
         * 
         * @param timeout maximum time to wait
         * @param unit the unit for the timeout parameter
         * 
         * @return the completed result, if the computation completed successfully.
         *
         * @throws CancellationException - if the computation was cancelled.
         * @throws ExecutionException - the the computation encountered an exception.
         * @throws InterruptedException - if the current thread was interrupted 
         *   while waiting.
         */
        public V get(long timeout, TimeUnit unit) 
            throws InterruptedException, ExecutionException, TimeoutException {
            long timeLimit = System.currentTimeMillis() + unit.toMillis(timeout);
            while(!hasEnded.get()) {
                long timeToWait = timeLimit - System.currentTimeMillis();
                if (timeToWait > 0) {
                    synchronized (this) {
                        wait(timeToWait);
                    }
                } else if (!hasEnded.get()) {
                    throw new TimeoutException();
                }
            }
            return getResultAfterHasEnded();
        }
        
        // Called by the get methods after their wait has been 
        // terminated.
        private V getResultAfterHasEnded() throws ExecutionException {
            if (outcome == TaskOutcome.SUCCESS) {
            	// Everything is ok, so just return the goods.
                return result;
            } 
            if (outcome == TaskOutcome.CANCELLED) {
            	// Was cancelled.
                throw new CancellationException();
            }
            if (outcome == TaskOutcome.ERROR) {
            	// Some sort of exception was encountered during the computation.
                throw new ExecutionException(error);
            }
            return result;
        }

        /** Returns true if this task has been paused. 
         */
        public boolean isPaused() {
        	return pauseFlag.get();
        }
        
        /**
         * Pause this task if it is running.
         */
        public synchronized void pause() {
        	if (this.isBegun() && !this.isEnded() && pauseFlag.compareAndSet(false, true)) {
        		notifyAll();
        	}
        }
        
        /**
         * Resumes this task if it is paused.
         */
        public synchronized void play() {
        	if (pauseFlag.compareAndSet(true, false)) {
        		notifyAll();
        	}
        }
        
        /**
         * Resets the task object, so it can be used again.  Never call this method 
         * on a task that has started but not finished.
         * 
         * @throws IllegalStateException - if the task is currently ongoing.
         */
        public void reset() {
            if (hasBegun.get()) {
                if (hasEnded.get()) {
                    outcome = TaskOutcome.NOT_FINISHED;
                    error = null;
                    errorMsg = null;
                    cancelFlag.set(false);
                    hasBegun.set(false);
                    hasEnded.set(false);
                    result = null;
                } else {
                    throw new IllegalStateException("cannot reset while running");
                }
            }
        }
        
        /**
         * Ensures posting of beginning and ending events to registered listeners
         * and calls <code>doTask()</code>, which must be defined by the
         * subclass.
         */
        public final void run() {
            
        	// Set mOwner to the current thread.  If another thread has already
            // gained the ownership, this will throw a RejectedExecutionException.
            obtainOwnership();
                        
            try {
                
            	// Reset in case the task is being reused.
                error = null;
                
                // Post an initial TaskEvent and set mHasBegun to true.
                postBegun();
                hasBegun.set(true);
                
                long nsStart = System.nanoTime();
                
                // Perform the work of the task by calling the 
                // subclass' doTask method.
                //
                result = doTask();
                
                long nsTaken = System.nanoTime() - nsStart;
            	
                postMessage(timeTakenString(nsTaken) + " to complete task");
                
                // In case error() was called off from off the owning thread,
                // possibly by a subtask thread.  (If called on the owning thread,
                // say directly from code in doTask(), error() tosses a RuntimeException.)
                //
                if (error != null) {
                    outcome = TaskOutcome.ERROR;
                    errorMsg = error.getMessage();
                    if (errorMsg == null) {
                        errorMsg = error.toString();
                    }
                }
                
            } catch (CancellationException ce) {
                
            	// Not an error -- this event is the result of cancellation.
                outcome = TaskOutcome.CANCELLED;
            
            } catch (InterruptedException ie) { 
                
            	// Methods such as sleep(), wait(), BlockingQueue.put(), BlockingQueue.take()
                // throw InterruptedExceptions if the execution thread is interrupted.  If
                // the cancel flag is true, then the task was probably canceled while blocking
                // in one of these methods.    
                if (isCancelled()) {
                    outcome = TaskOutcome.CANCELLED;
                }
                if (owner != null) {
                    // Since the methods that throw the InterruptedException clear the
                    // interrupt status, reset it so code farther up the call stack will
                    // see that the thread was interrupted.
                    owner.interrupt();
                }
            
            } catch (Throwable t) {
            
            	if (!(t instanceof TaskErrorException)) {
            		
            		// Stack traces for exceptions not thrown deliberately by
            		// error are quite useful for debugging.
                    LOGGER.error("Exception in task " + taskName(), t);
            	}
            	
            	error = t;
 
            	// Every exception or error other than a CancellationException
                // is regarded as an error.
                errorMsg = t.getMessage();
                
                if (errorMsg == null) {
                    errorMsg = t.toString();
                }
                
                outcome = TaskOutcome.ERROR;
            
            } finally {
            
            	if (outcome == TaskOutcome.NOT_FINISHED) {
                    outcome = TaskOutcome.SUCCESS;
                }
            	
                // Post the final event.
                postEnded();
                
                // Set mOwner back to null.
                releaseOwnership();            
            }
        }
        
        private static String timeTakenString(long nanoSeconds) {
        	long hours = nanoSeconds/3600000000000L;
        	if (hours > 0) {
        		nanoSeconds %= 3600000000000L;
        	}
        	long minutes = nanoSeconds/60000000000L;
        	if (minutes > 0) {
        		nanoSeconds %= 60000000000L;
        	}
        	long seconds = nanoSeconds/1000000000L;
        	if (seconds > 0) {
        		nanoSeconds %= 1000000000L;
        	}
        	long milliseconds = nanoSeconds/1000000L;
        	if (hours > 0L) {
        		return String.format("%d hours, %d minutes, and %d seconds", hours, minutes, seconds);
        	} else if (minutes > 0L) {
        		return String.format("%d minutes, %d seconds, and %d msec", minutes, seconds, milliseconds);
        	} else if (seconds > 0L) {
        		return String.format("%d seconds, %d msec", seconds, milliseconds);
        	} else {
        		return String.format("%d msec", milliseconds);
        	}
        }

        // Logically, only one thread should be executing the task at a time.  
        // If multiple threads were, they'd trample the data unless we encapsulated
        // everything in ThreadLocals.  Would this be worth the trouble? Nah.
        //
        private synchronized void obtainOwnership() {
            if (owner != null) {
                throw new RejectedExecutionException();
            }
            owner = Thread.currentThread();
        }
        
        // Release a thread's ownership after finishing up a run.
        //
        private synchronized void releaseOwnership() {
            if (Thread.currentThread() == owner) {
                owner = null;
            }
        }
        
        /**
         * Cancel the AbstractTask.
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!isBegun() || mayInterruptIfRunning) {
                if (!(cancelFlag.get() || isEnded())) {
                    cancelFlag.set(true);
                    // Break from the hang in checkForCancel() if
                    // paused.
                    if (pauseFlag.get()) {
                    	synchronized (this) {
                    		notifyAll();
                    	}
                    }
                    if (owner != null) {
                        synchronized (this) {
                            if (owner != null) {
                                // The thread executing the task may
                                // be in a blocking operation of some kind
                                // which interrupt might unblock.
                                owner.interrupt();
                            }
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        
        @Override
        /** 
         * This version is mandated by the Future<V> interface.
         */
        public boolean isCancelled() {
            return cancelFlag.get();
        }
        
//        @Override
//        /**
//         * This one is mandated by the Cancelable interface.
//         */
//        public boolean isCancelled() {
//        	return isCancelled();
//        }
        
        /**
         * Same as isEnded().
         * 
         * @return true if the task was started and finished.
         *    How it finished is determined by call getTaskOutcome().
         */
        public boolean isDone() {
            return hasEnded.get();
        }

        /**
         * Subclasses error out by calling this method.  It emits a <code>
         * RuntimeException</code> which is trapped in the final run method.  The run method's
         * catch takes care of setting the error message.
         * 
         * When subclasses call this method on the thread executing run(), the
         * controller thread, the controller thread is immediately interrupted by a 
         * run time exception which it catches and posts to TaskListeners via a 
         * TaskEvent with an error outcome.  But if this method
         * is called off of the controlling thread, perhaps by a subtask thread,
         * the controller is not immediately interrupted.  However, the controller still 
         * reports an error outcome at the termination of the task.
         * 
         * @param errorMsg String
         */
        protected void finishWithError(String errorMsg) {
            if (Thread.currentThread() == owner) {
                throw new TaskErrorException(errorMsg);
            } else {
                error = new TaskErrorException(errorMsg);
            }
        }

        /**
         * Returns the error message associated with an outcome of TaskOutcome.ERROR.
         * Null is returned if the outcome is anything else or if the AbstractTask is not
         * finished.
         * @return String
         */
        public String getErrorMessage() {
            return errorMsg;
        }
        
        /**
         * Returns the throwable (error or exception) which aborted execution,
         * if such a condition terminated the run.
         * 
         * @return a <code>Throwable</code> or null if no error occurred.
         */
        public Throwable getError() {
        	return error;
        }

        /**
         * Get the current progress.
         * @return double
         */
        public double getProgress() {
            return progress;
        }

        /**
         * Subclasses define this method to do their work.
         * 
         * @return the product of the task.
         * 
         * @throws Exception if an error occurs during performance of the task.
         */
        protected abstract V doTask() throws Exception;

        /**
         * Returns true if the AbstractTask has begun, false otherwise.
         * @return boolean
         */
        public boolean isBegun() {
            return hasBegun.get();
        }

        /**
         * Returns true if the AbstractTask has finished, false otherwise.
         * @return boolean
         */
        public boolean isEnded() {
            return isDone();
        }

        /**
         * Called after the final event to get the outcome of the AbstractTask.  Possible
         * return values are:
         * <ul>
         * <li>TaskOutcome.NOT_FINISHED
         * <li>TaskOutcome.CANCELED
         * <li>TaskOutcome.ERROR
         * <li>TaskOutcome.SUCCESS
         * </ul>
         * @return TaskOutcome
         */
        public final TaskOutcome getTaskOutcome() {
            return outcome;
        }

        // Posts the first TaskEvent to registered listeners by calling their
        // taskBegun() methods.
        private void postBegun() {
        	if (!hasBegun.get()) {
        		eventSupport.fireTaskBegun();
            }
        }

        // Posts a message to registered listeners by calling their taskMessage() methods,
        // but first checks status flags and for cancellation.
        protected void postMessage(String msg) {
            if (hasBegun.get() && !hasEnded.get()) {
                checkForCancel();
            }
            transmitMessage(msg);
        }
        
        // Post a message to listeners with no cancellation check.
        private void transmitMessage(String msg) {
        	eventSupport.fireTaskMessage(msg);
        }

        // Notifies registered listeners of the current progress by calling
        // their taskProgress() methods.
        protected void postProgress(double progress) {
            if (hasBegun.get() && !hasEnded.get()) {
                checkForCancel();
                this.progress = progress;
                eventSupport.fireTaskProgress();
            }
        }

        private void postEnded() {
            if (hasBegun.get() && !hasEnded.get()) {
                hasEnded.set(true);
                synchronized (this) {
                    notifyAll();
                }
                eventSupport.fireTaskEnded();
            }
        }
        
        protected void checkForCancel() {
        	// Whenever cancelled, pop a CancellationException
        	if (cancelFlag.get()) {
        		throw new CancellationException();
        	}
        	// But if paused, hang here until play() is called.
        	if (pauseFlag.get()) {
        		// Post a pause message, but not through postMessage(), since
        		// it calls this method.
        		eventSupport.fireTaskPaused();
        		// Hang in this loop until either play() or cancel() is called.
        		synchronized (this) {
        			while (pauseFlag.get() && !cancelFlag.get()) {
        				try {
        					wait(1000L);
        				} catch (InterruptedException ie) {
        				}
        			}
        		}
            	// Whenever cancelled, pop a CancellationException
            	if (cancelFlag.get()) {
            		throw new CancellationException();
            	}
            	eventSupport.fireTaskResumed();
        	}
        }
        
        /**
         * Thrown by the error() method of AbstractTask.  The subclassing is done
         * just so the catch in the run method can differient exceptions thrown by 
         * error from NullPointerExceptions and the like.
         * 
         * @author D3J923
         *
         */
        public static class TaskErrorException extends RuntimeException {
        	
        	/**
			 * 
			 */
			private static final long serialVersionUID = 1L;

			public TaskErrorException(String message) {
        		super(message);
        	}
        	
        }
}
