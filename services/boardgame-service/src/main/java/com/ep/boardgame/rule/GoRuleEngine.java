package com.ep.boardgame.rule;

import com.ep.boardgame.common.PlayerSide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D-191: Go (围棋) rule engine for a 19x19 board.
 * <p>
 * Board representation: a 19x19 {@code int} array where
 * <ul>
 *   <li>{@code 0} = empty intersection,</li>
 *   <li>{@code 1} = Black stone ({@link PlayerSide#RED} in platform terms — Black plays first),</li>
 *   <li>{@code 2} = White stone ({@link PlayerSide#BLACK} in platform terms).</li>
 * </ul>
 * The platform reuses {@link PlayerSide}: RED maps to the Black stones
 * (first player in Go) and BLACK maps to White, consistent with Xiangqi
 * where RED moves first.
 * <p>
 * Supported operations:
 * <ul>
 *   <li>{@link #applyMove} — place a stone, resolve captures, detect ko.</li>
 *   <li>{@link #isLegalMove} — validates occupation, suicide (unless capturing),
 *       and simple positional superko via the previous board hash.</li>
 *   <li>{@link #determineResult} — Chinese area scoring (area = stones +
 *       territory, 7.5 komi for White).</li>
 *   <li>{@link #toSgf} / {@link #parseSgf} — SGF serialization for replay storage.</li>
 * </ul>
 * <p>
 * Ko rule: the engine tracks the immediately preceding board position and
 * forbids a move that would exactly reproduce it (simple ko). Full positional
 * superko is not enforced to keep the engine lightweight for teaching scenarios.
 */
public class GoRuleEngine {

    /** Board size (19x19). */
    public static final int SIZE = 19;

    /** Empty intersection. */
    public static final int EMPTY = 0;
    /** Black stone (first player, platform {@link PlayerSide#RED}). */
    public static final int BLACK = 1;
    /** White stone (second player, platform {@link PlayerSide#BLACK}). */
    public static final int WHITE = 2;

    /** Komi for White under Chinese scoring. */
    public static final double KOMI = 7.5;

    // --- Factory / construction -------------------------------------------

    /**
     * Create an empty 19x19 board.
     */
    public int[][] newBoard() {
        int[][] b = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            b[r] = new int[SIZE];
        }
        return b;
    }

    // --- Core rule logic --------------------------------------------------

    /**
     * Apply a move (stone placement) and return the resulting board.
     * <p>
     * The returned board is a fresh copy; the input board is not mutated.
     *
     * @param board  current position (19x19)
     * @param row    row index [0,18]
     * @param col    col index [0,18]
     * @param stone  {@link #BLACK} or {@link #WHITE}
     * @return a new board after applying the move and resolving captures
     * @throws IllegalArgumentException if the move is illegal
     */
    public int[][] applyMove(int[][] board, int row, int col, int stone) {
        validateBounds(row, col);
        if (stone != BLACK && stone != WHITE) {
            throw new IllegalArgumentException("Invalid stone: " + stone);
        }
        if (board[row][col] != EMPTY) {
            throw new IllegalArgumentException("Intersection (" + row + "," + col + ") is occupied");
        }
        int[][] next = copy(board);
        next[row][col] = stone;

        // Capture opponent groups with no liberties around the placed stone.
        int opponent = (stone == BLACK) ? WHITE : BLACK;
        int captured = 0;
        for (int[] d : ADJACENT) {
            int nr = row + d[0], nc = col + d[1];
            if (inBounds(nr, nc) && next[nr][nc] == opponent) {
                Set<Long> group = getGroup(next, nr, nc);
                if (getLiberties(next, group) == 0) {
                    captured += captureGroup(next, group);
                }
            }
        }

        // Suicide check: if the placed stone's group has no liberties and
        // did not capture anything, the move is illegal.
        Set<Long> ownGroup = getGroup(next, row, col);
        if (getLiberties(next, ownGroup) == 0 && captured == 0) {
            throw new IllegalArgumentException("Suicide move at (" + row + "," + col + ")");
        }
        return next;
    }

    /**
     * Check whether a move is legal without applying it.
     */
    public boolean isLegalMove(int[][] board, int row, int col, int stone) {
        try {
            validateBounds(row, col);
            if (stone != BLACK && stone != WHITE) return false;
            if (board[row][col] != EMPTY) return false;

            int[][] next = copy(board);
            next[row][col] = stone;
            int opponent = (stone == BLACK) ? WHITE : BLACK;
            int captured = 0;
            for (int[] d : ADJACENT) {
                int nr = row + d[0], nc = col + d[1];
                if (inBounds(nr, nc) && next[nr][nc] == opponent) {
                    Set<Long> group = getGroup(next, nr, nc);
                    if (getLiberties(next, group) == 0) {
                        captured += captureGroup(next, group);
                    }
                }
            }
            Set<Long> ownGroup = getGroup(next, row, col);
            return getLiberties(next, ownGroup) > 0 || captured > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Check legality with simple-ko detection against the previous board.
     *
     * @param previous  the board state before the current one (may be null)
     */
    public boolean isLegalMove(int[][] board, int[][] previous, int row, int col, int stone) {
        if (!isLegalMove(board, row, col, stone)) return false;
        if (previous == null) return true;
        int[][] next = applyMove(board, row, col, stone);
        return !equalsBoard(next, previous);
    }

    // --- Group / liberty computation --------------------------------------

    /**
     * Flood-fill the connected group of same-colored stones containing (row,col).
     *
     * @return a set of encoded positions {@code row * SIZE + col}
     */
    public Set<Long> getGroup(int[][] board, int row, int col) {
        int color = board[row][col];
        Set<Long> group = new HashSet<>();
        if (color == EMPTY) return group;
        Deque<long[]> stack = new ArrayDeque<>();
        stack.push(new long[]{row, col});
        while (!stack.isEmpty()) {
            long[] cur = stack.pop();
            long key = cur[0] * SIZE + cur[1];
            if (group.contains(key)) continue;
            int r = (int) cur[0], c = (int) cur[1];
            if (board[r][c] != color) continue;
            group.add(key);
            for (int[] d : ADJACENT) {
                int nr = r + d[0], nc = c + d[1];
                if (inBounds(nr, nc) && board[nr][nc] == color && !group.contains((long) nr * SIZE + nc)) {
                    stack.push(new long[]{nr, nc});
                }
            }
        }
        return group;
    }

    /**
     * Count the liberties (empty adjacent intersections) of a group.
     */
    public int getLiberties(int[][] board, Set<Long> group) {
        Set<Long> liberties = new HashSet<>();
        for (long key : group) {
            int r = (int) (key / SIZE), c = (int) (key % SIZE);
            for (int[] d : ADJACENT) {
                int nr = r + d[0], nc = c + d[1];
                if (inBounds(nr, nc) && board[nr][nc] == EMPTY) {
                    liberties.add((long) nr * SIZE + nc);
                }
            }
        }
        return liberties.size();
    }

    /**
     * Remove all stones in a group from the board (set to EMPTY).
     *
     * @return the number of stones captured
     */
    public int captureGroup(int[][] board, Set<Long> group) {
        int count = 0;
        for (long key : group) {
            int r = (int) (key / SIZE), c = (int) (key % SIZE);
            board[r][c] = EMPTY;
            count++;
        }
        return count;
    }

    // --- Scoring (Chinese area scoring) -----------------------------------

    /**
     * Determine the game result using Chinese area scoring.
     * <p>
     * Area score = stones of that color + territory (empty regions reachable
     * only by that color). White receives {@link #KOMI} (7.5).
     *
     * @return {@code "BLACK_WIN"}, {@code "WHITE_WIN"}, or {@code "DRAW"}
     */
    public String determineResult(int[][] board) {
        int blackArea = 0, whiteArea = 0;
        boolean[][] visited = new boolean[SIZE][SIZE];

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == BLACK) {
                    blackArea++;
                } else if (board[r][c] == WHITE) {
                    whiteArea++;
                } else if (!visited[r][c]) {
                    // Flood-fill empty region; track which colors border it.
                    Set<Long> region = new HashSet<>();
                    Set<Integer> borderColors = new HashSet<>();
                    Deque<int[]> stack = new ArrayDeque<>();
                    stack.push(new int[]{r, c});
                    while (!stack.isEmpty()) {
                        int[] cur = stack.pop();
                        int cr = cur[0], cc = cur[1];
                        long key = (long) cr * SIZE + cc;
                        if (region.contains(key)) continue;
                        region.add(key);
                        visited[cr][cc] = true;
                        for (int[] d : ADJACENT) {
                            int nr = cr + d[0], nc = cc + d[1];
                            if (!inBounds(nr, nc)) continue;
                            if (board[nr][nc] == EMPTY) {
                                if (!visited[nr][nc]) stack.push(new int[]{nr, nc});
                            } else {
                                borderColors.add(board[nr][nc]);
                            }
                        }
                    }
                    // Territory belongs to a color only if it's the sole border.
                    if (borderColors.size() == 1) {
                        int owner = borderColors.iterator().next();
                        if (owner == BLACK) blackArea += region.size();
                        else whiteArea += region.size();
                    }
                }
            }
        }

        double blackTotal = blackArea;
        double whiteTotal = whiteArea + KOMI;
        if (blackTotal > whiteTotal) return "BLACK_WIN";
        if (whiteTotal > blackTotal) return "WHITE_WIN";
        return "DRAW";
    }

    // --- SGF serialization ------------------------------------------------

    /**
     * Serialize a move list to SGF format.
     * <p>
     * Coordinates use SGF convention: letters a-s for both row and column
     * (top-left = aa).
     *
     * @param moves list of moves [row, col, stone] where stone is {@link #BLACK} or {@link #WHITE}
     * @return an SGF fragment like {@code ;B[ab];W[cg]...}
     */
    public String toSgf(List<int[]> moves) {
        StringBuilder sb = new StringBuilder();
        for (int[] m : moves) {
            int row = m[0], col = m[1], stone = m[2];
            char color = (stone == BLACK) ? 'B' : 'W';
            char r = (char) ('a' + row);
            char c = (char) ('a' + col);
            sb.append(';').append(color).append('[').append(c).append(r).append(']');
        }
        return sb.toString();
    }

    /**
     * Parse an SGF move sequence into a list of [row, col, stone] arrays.
     * <p>
     * Accepts fragments like {@code ;B[ab];W[cd]}.
     *
     * @return list of moves; each entry is {@code {row, col, stone}}
     */
    public List<int[]> parseSgf(String sgf) {
        List<int[]> moves = new ArrayList<>();
        if (sgf == null || sgf.isBlank()) return moves;
        int i = 0, n = sgf.length();
        while (i < n) {
            // Skip to the next ';' or color letter.
            char ch = sgf.charAt(i);
            if (ch == ';') { i++; continue; }
            if (ch == 'B' || ch == 'W') {
                int stone = (ch == 'B') ? BLACK : WHITE;
                // Expect '['
                if (i + 1 >= n || sgf.charAt(i + 1) != '[') {
                    throw new IllegalArgumentException("Invalid SGF at index " + i);
                }
                int close = sgf.indexOf(']', i + 2);
                if (close == -1 || close - (i + 2) < 2) {
                    throw new IllegalArgumentException("Invalid SGF coord at index " + i);
                }
                char c = sgf.charAt(i + 2);
                char r = sgf.charAt(i + 3);
                int col = c - 'a';
                int row = r - 'a';
                validateBounds(row, col);
                moves.add(new int[]{row, col, stone});
                i = close + 1;
            } else {
                i++;
            }
        }
        return moves;
    }

    // --- Helpers ----------------------------------------------------------

    private static final int[][] ADJACENT = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private void validateBounds(int row, int col) {
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) {
            throw new IllegalArgumentException("Position (" + row + "," + col + ") out of bounds");
        }
    }

    private int[][] copy(int[][] board) {
        int[][] c = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            System.arraycopy(board[r], 0, c[r], 0, SIZE);
        }
        return c;
    }

    private boolean equalsBoard(int[][] a, int[][] b) {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (a[r][c] != b[r][c]) return false;
            }
        }
        return true;
    }

    /**
     * Map a platform {@link PlayerSide} to a Go stone constant.
     * RED -> {@link #BLACK} (first player), BLACK -> {@link #WHITE}.
     */
    public static int stoneOf(PlayerSide side) {
        return side == PlayerSide.RED ? BLACK : WHITE;
    }
}
