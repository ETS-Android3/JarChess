package com.example.jarchess.match.events;

import com.example.jarchess.match.result.ChessMatchResult;

public class MatchResultIsInEvent {
    final ChessMatchResult matchChessMatchResult;

    public MatchResultIsInEvent(ChessMatchResult matchChessMatchResult) {
        this.matchChessMatchResult = matchChessMatchResult;
    }

    public ChessMatchResult getMatchChessMatchResult() {
        return matchChessMatchResult;
    }
}
