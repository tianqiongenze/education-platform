package com.ep.boardgame.room;

import com.ep.boardgame.common.PlayerSide;
import com.ep.boardgame.rule.Move;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * DTO for making a move in a match.
 */
public class MakeMoveCmd implements Serializable {

    private Long matchId;
    @NotNull
    private Long playerId;
    private int fromRow;
    private int fromCol;
    private int toRow;
    private int toCol;

    public Long getMatchId() { return matchId; }
    public void setMatchId(Long matchId) { this.matchId = matchId; }
    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public int getFromRow() { return fromRow; }
    public void setFromRow(int fromRow) { this.fromRow = fromRow; }
    public int getFromCol() { return fromCol; }
    public void setFromCol(int fromCol) { this.fromCol = fromCol; }
    public int getToRow() { return toRow; }
    public void setToRow(int toRow) { this.toRow = toRow; }
    public int getToCol() { return toCol; }
    public void setToCol(int toCol) { this.toCol = toCol; }

    /**
     * Convert to a Move value object. The player side and move number are
     * determined by the service layer (which knows the match state).
     */
    public Move toMove(PlayerSide side, int moveNumber) {
        return new Move(fromRow, fromCol, toRow, toCol, side, moveNumber);
    }
}
