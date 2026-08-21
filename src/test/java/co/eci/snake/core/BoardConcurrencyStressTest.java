package co.eci.snake.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class BoardConcurrencyStressTest {

  @Test
  void supportsTwentySnakesWithoutConcurrentCollectionFailures() throws Exception {
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
      Board board = new Board(35, 28); // Includes teleports and turbo items.
      List<Snake> snakes = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        snakes.add(Snake.of(2 + (i * 3) % board.width(),
            2 + (i * 2) % board.height(), Direction.values()[i % 4]));
      }

      CountDownLatch ready = new CountDownLatch(snakes.size());
      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> tasks = new ArrayList<>();
        for (Snake snake : snakes) {
          tasks.add(executor.submit(() -> {
            ready.countDown();
            start.await();
            for (int move = 0; move < 2_000; move++) {
              snake.turn(Direction.values()[ThreadLocalRandom.current().nextInt(4)]);
              board.step(snake);
              assertFalse(snake.snapshot().isEmpty());
            }
            return null;
          }));
        }

        ready.await();
        start.countDown();
        for (Future<?> task : tasks) {
          task.get();
        }
      }

      assertTrue(board.mice().size() > 0);
      assertTrue(board.teleports().size() > 0);
    });
  }
}
