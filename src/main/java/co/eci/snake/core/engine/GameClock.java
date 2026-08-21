package co.eci.snake.core.engine;

import co.eci.snake.core.GameState;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class GameClock implements AutoCloseable {
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final long periodMillis;
  private final Runnable tick;
  private final AtomicReference<GameState> state = new AtomicReference<>(GameState.STOPPED);
  /**
   * A runner holds the read lock while it changes the game model.  Pausing
   * takes the write lock, therefore when pauseAndAwaitQuiescence returns there
   * cannot be a move halfway through.
   */
  private final ReentrantReadWriteLock movementLock = new ReentrantReadWriteLock();

  public GameClock(long periodMillis, Runnable tick) {
    if (periodMillis <= 0) throw new IllegalArgumentException("periodMillis must be > 0");
    this.periodMillis = periodMillis;
    this.tick = Objects.requireNonNull(tick, "tick");
  }

  public void start() {
    if (state.compareAndSet(GameState.STOPPED, GameState.RUNNING)) {
      synchronized (this) {
        notifyAll();
      }
      scheduler.scheduleAtFixedRate(() -> {
        if (state.get() == GameState.RUNNING) tick.run();
      }, 0, periodMillis, TimeUnit.MILLISECONDS);
    }
  }

  public void pauseAndAwaitQuiescence()  {
    movementLock.writeLock().lock();
    try {
      state.compareAndSet(GameState.RUNNING, GameState.PAUSED);
    } finally {
      movementLock.writeLock().unlock();
    }
  }

    public synchronized void resume() {
      if (state.compareAndSet(GameState.PAUSED, GameState.RUNNING)) {
        notifyAll();
      }
    }

  /**
   * Waits for the game to run and reserves a short, consistent movement
   * section. The returned permit must be closed immediately after the move.
   */
  public MovementPermit awaitMovement() throws InterruptedException {
    for (;;) {
      synchronized (this) {
        while (state.get() != GameState.RUNNING) {
          wait();
        }
      }

      movementLock.readLock().lockInterruptibly();
      if (state.get() == GameState.RUNNING) {
        return new MovementPermit();
      }
      movementLock.readLock().unlock();
    }
  }

  public GameState state() { return state.get(); }

  public synchronized void stop() {
    state.set(GameState.STOPPED);
    notifyAll();
  }

  @Override public void close() {
    stop();
    scheduler.shutdownNow();
  }

  public final class MovementPermit implements AutoCloseable {
    private boolean closed;

    private MovementPermit() { }

    @Override public void close() {
      if (!closed) {
        closed = true;
        movementLock.readLock().unlock();
      }
    }
  }
}
