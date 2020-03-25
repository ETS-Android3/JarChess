package com.example.jarchess.match.activity;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.jarchess.JarAccount;
import com.example.jarchess.R;
import com.example.jarchess.match.ChessColor;
import com.example.jarchess.match.Coordinate;
import com.example.jarchess.match.Match;
import com.example.jarchess.match.MatchHistory;
import com.example.jarchess.match.clock.MatchClock;
import com.example.jarchess.match.clock.MatchClockObserver;
import com.example.jarchess.match.move.Move;
import com.example.jarchess.match.move.PieceMovement;
import com.example.jarchess.match.participant.LocalParticipantController;
import com.example.jarchess.match.pieces.Pawn;
import com.example.jarchess.match.pieces.Piece;
import com.example.jarchess.match.result.Result;
import com.example.jarchess.match.turn.Turn;
import com.example.jarchess.match.view.CommitButtonClickObserver;
import com.example.jarchess.match.view.MatchView;
import com.example.jarchess.match.view.SquareClickHandler;

import java.util.Collection;
import java.util.LinkedList;

import static androidx.constraintlayout.widget.Constraints.TAG;

/**
 * A match activity is an activity where two participants play a chess match with each other.
 *
 * @author Joshua Zierman
 */
public abstract class MatchActivity extends AppCompatActivity
        implements LocalParticipantController,
        SquareClickHandler,
        CommitButtonClickObserver,
        MatchClockObserver {

    private static final long MATCH_CLOCK_TOLERANCE_MILLIS = 1000L;
    private final Collection<Coordinate> possibleDestinations;
    private volatile ChessColor waitingForMove;
    private volatile Move move;
    private volatile Piece.PromotionChoice promotionChoiceInput = null;
    private volatile Coordinate observedSquareClickCoordinate = null;
    private volatile boolean commitButtonHasBeenPressed = false;
    private Match match;
    private MatchHistory matchHistory;
    private Coordinate originInput;
    private Coordinate destinationInput;
    private MatchView matchView;
    private boolean resultWasShown = false;
    private MatchClock matchClock;

    public MatchActivity() {
        this.possibleDestinations = new LinkedList<Coordinate>();
    }


    /**
     * clears the destinationInput
     */
    private void clearDestinationInput() {
        Log.v(TAG, "clearDestination() called");

        if (destinationInput == null) {
            return;
        }

        matchView.clearDestinationSelectionIndicator(destinationInput);

        this.destinationInput = null;
    }

    private void clearInputValues() {
        move = null;
        clearOriginInput();
        clearDestinationInput();
    }

    /**
     * clears the originInput
     */
    private void clearOriginInput() {
        Log.v(TAG, "clearOrigin() called");

        if (originInput == null) {
            return;
        }
        matchView.clearOriginSelectionIndicator(originInput);
        matchView.clearPossibleDestinationIndicators(possibleDestinations);
        matchView.clearDestinationSelectionIndicator(destinationInput);


        this.originInput = null;
        possibleDestinations.clear();
    }

    private void clearPossibleInputDestinations() {
    }

    private synchronized void commit() {
        if (originInput != null && destinationInput != null) {

            LinkedList<PieceMovement> movements = new LinkedList<PieceMovement>(match.getLegalCastleMovements(originInput, destinationInput));

            if (movements.isEmpty()) {
                movements.add(new PieceMovement(originInput, destinationInput));
            }
            move = new Move(movements);
            waitingForMove = null;
            this.notifyAll();

            clearDestinationInput();
            clearOriginInput();
        }
    }

    private boolean commitButtonClickRequired() {
        return JarAccount.getInstance().getCommitButtonClickIsRequired();
    }

    public abstract Match createMatch();

    private synchronized void conditionallyThrowMatchOverException() throws MatchOverException {
        if (match.isDone()) {
            throw new MatchOverException(match.getMatchResult());
        }
    }

    private void execute(Turn turn) {
        Log.v(TAG, "execute is running on thread: " + Thread.currentThread().getName());
        Move move = turn.getMove();
        for (PieceMovement movement : move) {

            Coordinate origin = movement.getOrigin();
            Coordinate destination = movement.getDestination();

            if (match.getPieceAt(destination) != null) {
                //perform normal capture
                Piece capturedPiece = match.capture(destination);
                matchView.addCapturedPiece(capturedPiece);
            } else if (matchHistory.getEnPassantVulnerableCoordinate() == destination && match.getPieceAt(origin) instanceof Pawn) {
                // perform en passant capture
                Piece capturedPiece = match.capture(matchHistory.getEnPassentRiskedPieceLocation());
                matchView.addCapturedPiece(capturedPiece);
                matchView.updatePiece(matchHistory.getEnPassentRiskedPieceLocation());
            }
            match.move(movement.getOrigin(), movement.getDestination());
            Piece.PromotionChoice choice = turn.getPromotionChoice();
            if (choice != null) {
                match.promote(destination, choice);
            }
        }
        matchHistory.add(turn);
        match.checkForGameEnd(ChessColor.getOther(turn.getColor()));
    }

    private void exitActivity() {
        matchClock.stop(); // stops the clock
        match.setIsDone(true); // makes sure the match thread ends
        super.onBackPressed();
    }

    @Override
    public Move getMoveInput(ChessColor color) throws InterruptedException, MatchOverException {
        Log.v(TAG, "getMove() called with: color = [" + color + "]");

        // clear all of the move related fields to make sure we start fresh.
        clearInputValues();

        // set the color that is waiting for move
        waitingForMove = color;

        // process input until the move is constructed
        while (move == null) {
            processNextInput();
        }

        // return the move
        return move;
    }

    private boolean isNewDestinationInput(Coordinate coordinateClicked) {
        return originInput != null && destinationInput != coordinateClicked && possibleDestinations.contains(coordinateClicked);
    }

    private boolean isNewOrginInput(Coordinate coordinateClicked, Piece clickedPiece) {
        return clickedPiece != null && clickedPiece.getColor() == waitingForMove && originInput != coordinateClicked;
    }

    @Override
    public synchronized void observeCommitButtonClick() {

        // won't process if there is a pending square click to be processed.

        if (observedSquareClickCoordinate == null && !commitButtonHasBeenPressed)
            commitButtonHasBeenPressed = true;
        notifyAll();
    }

    @Override
    public synchronized Piece.PromotionChoice getPromotionChoice(Move move) throws InterruptedException, MatchOverException {

        if (promotionChoiceInput != null) {
            promotionChoiceInput = null;
            this.notifyAll();// promotionChoiceInput has been changed
        }

        Piece p;
        for (PieceMovement movement : move) {
            p = match.getPieceAt(movement.getOrigin());


            if (p instanceof Pawn && (movement.getDestination().getRank() == 1 || movement.getDestination().getRank() == 8)) {

                matchView.updateViewBefore(movement);
                matchView.setPromotionIndicator(movement.getDestination());
                matchView.showPawnPromotionChoiceDialog();
                while (promotionChoiceInput == null) {
                    myWait();
                }
                matchView.clearPromotionIndicator(movement);
            }
        }

        return promotionChoiceInput;
    }

    private void handleSquareClick(Coordinate coordinateClicked) {
        // log the click
        Log.v(TAG, "observeSquareClick() called with: coordinateClicked = " + coordinateClicked);


        Piece clickedPiece = match.getPieceAt(coordinateClicked);
        if (waitingForMove != null) {
            // Than one of the participants is waiting for a move.

            if (isNewOrginInput(coordinateClicked, clickedPiece)) {
                Log.v(TAG, "handleSquareClick: treating the coordinate as newly selected origin");
                // If the square has a piece and it is the color of the waiting participant
                // and it is not already the current originInput,
                //
                // than we assume that the click indicates that the user intends to set that square as the new originInput of the move

                // set the originInput
                clearOriginInput();
                clearDestinationInput();
                clearPossibleInputDestinations();
                setOriginInput(coordinateClicked);
                updatePossibleInputDestinations(originInput);


            } else if (isNewDestinationInput(coordinateClicked)) {
                Log.v(TAG, "handleSquareClick: Treating the coordinate as newly selected destination");
                // If the originInput was already set
                // and the square was empty or had a piece that was a different color than the participant waiting for a move to be input,
                // and the square is in the set of possible destinations
                //
                // than we assume that the click indicates that the user intends to set that square as the destinationInput of the move.

                setDestinationInput(coordinateClicked);

                if (!commitButtonClickRequired()) {
                    Log.v(TAG, "handleSquareClick: commit button is not required, so commit is immediate");
                    observeCommitButtonClick();
                }

            } else {
                Log.v(TAG, "handleSquareClick: The coordinate is not a new origin or destination");
            }
        }
    }

    private void myWait() throws InterruptedException, MatchOverException {
        this.wait();
        conditionallyThrowMatchOverException();
    }

    @Override
    public synchronized void observeMatchClock(MatchClock matchClock) {
        if (matchClock.flagHasFallen()) {
            if (match.getMatchResult() == null) {
                match.checkForTimeout();
            }
            this.notifyAll();
        }

        matchView.updateTimes(matchClock);
    }

    public void observeResignButtonClick() {

        // do anything we need to do before match activity ends

        exitActivity();
    }

    public void observeResultAcknowledgement() {
        // do anything we need to do before match activity ends

        exitActivity();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void observeSquareClick(Coordinate coordinateClicked) {

        // won't process if there is a pending commit button click to be processed.

        if (observedSquareClickCoordinate != coordinateClicked && !commitButtonHasBeenPressed) {
            observedSquareClickCoordinate = coordinateClicked;
            Log.v(TAG, "observeSquareClick: at " + coordinateClicked);
            this.notifyAll();
        }

    }

    @Override
    public void onBackPressed() {
        if (resultWasShown) {
            exitActivity();
        } else {
            matchView.showLeaveMatchDialog();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // set match to the current match
        match = createMatch();
        matchHistory = match.getMatchHistory();

        setContentView(R.layout.activity_match);
        matchView = new MatchView(match, this);

        matchClock = match.getMatchClock();

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    Log.v(TAG, "run is running on thread: " + Thread.currentThread().getName());

                    playMatch();
                } finally {

                    Log.v(TAG, "thread is done running: " + Thread.currentThread().getName());
                }
            }
        };
        new Thread(runnable, "MatchThread").start();

        Log.v("MatchActivity", "created");
    }

    private void playMatch() {
        Turn turn;


        try {
            matchClock.start();
            turn = match.getFirstTurn();
            matchClock.syncEnd(turn.getColor(), turn.getElapsedTime());
            validate(turn);
            execute(turn);
            matchView.updateViewAfter(turn);

            while (!match.isDone()) {
                turn = match.getTurn(turn);
                matchClock.syncEnd(turn.getColor(), turn.getElapsedTime());
                validate(turn);
                execute(turn);
                matchView.updateViewAfter(turn);
            }
        } catch (MatchOverException e) {
            match.setIsDone(true);
        } catch (InterruptedException e2) {
            match.setIsDone(true);
        }

        matchClock.stop();

        showMatchResult();
    }

    private void processNextInput() throws MatchOverException, InterruptedException {
        Coordinate coordinateToProcess = null;
        boolean commitButtonPressNeedsProcessing = false;
        synchronized (this) {
            while (observedSquareClickCoordinate == null && !commitButtonHasBeenPressed) {
                myWait();
            }
            if (observedSquareClickCoordinate != null) {
                coordinateToProcess = observedSquareClickCoordinate;
                observedSquareClickCoordinate = null;
                notifyAll();
            }

            if (commitButtonHasBeenPressed) {
                commitButtonPressNeedsProcessing = true;
                commitButtonHasBeenPressed = false;
                notifyAll();
            }
        }
        if (coordinateToProcess != null && commitButtonPressNeedsProcessing) {
            String msg = "processNextInput: Both square click and commit button click needs processing";
            Log.e(TAG, msg);
            throw new IllegalStateException(msg);
        }

        // handle commit
        if (commitButtonPressNeedsProcessing) {
            if (originInput != destinationInput && originInput != null && destinationInput != null) {
                commit();
            }
        } else if (coordinateToProcess != null) {

            handleSquareClick(coordinateToProcess);
        }

    }

    public synchronized void setPromotionChoiceInput(Piece.PromotionChoice promoteToRook) {
        promotionChoiceInput = promoteToRook;
        this.notifyAll();
    }

    /**
     * Sets the destinationInput.
     *
     * @param destination the destinationInput coordinate to set to, not null
     */
    private void setDestinationInput(@NonNull Coordinate destination) {
        Log.v(TAG, "setDestinationInput() called with: destinationInput = [" + destinationInput + "]");

        if (destinationInput != null) {
            matchView.clearDestinationSelectionIndicator(destinationInput);
        }
        destinationInput = destination;
        matchView.setDestinationSelectionIndicator(destination);

    }

    /**
     * Sets the originInput.
     *
     * @param origin the originInput coordinate to set to, not null
     */
    private void setOriginInput(@NonNull Coordinate origin) {
        Log.v(TAG, "setOriginInput() called with: originInput = [" + origin + "]");

        if (originInput != null) {
            matchView.clearOriginSelectionIndicator(originInput);
        }
        originInput = origin;
        matchView.updateAfterSettingOrigin(origin);

    }

    private void validate(Turn turn) {
        //TODO
    }

    private void showMatchResult() {
        Log.v(TAG, "showMatchResult() called");
        Log.v(TAG, "showMatchResult: " + match.getMatchResult());
        resultWasShown = true;

        matchView.showMatchResultDialog(match.getMatchResult());
    }

    private void updatePossibleInputDestinations(@NonNull Coordinate originInput) {
        matchView.clearPossibleDestinationIndicators(possibleDestinations);
        possibleDestinations.addAll(match.getPossibleMoves(originInput));
        matchView.updateAfterSettingPossibleDestinations(possibleDestinations);
    }

    public static class MatchOverException extends Exception {

        private final Result matchResult;

        public MatchOverException(Result matchResult) {
            super("MatchOverException with result: " + matchResult);
            this.matchResult = matchResult;
        }

        public Result getMatchResult() {
            return matchResult;
        }
    }

}
