package be.proleague.model.presentation

import be.proleague.model.adapter.sofascore.SofaScoreException
import be.proleague.model.adapter.sofascore.SofaScoreProperties
import be.proleague.model.application.IncompleteStandingsException
import be.proleague.model.application.PredictionService
import be.proleague.model.application.RoundAlreadyStartedException
import be.proleague.model.application.StandingsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api")
class RoundController(
    private val predictionService: PredictionService,
    private val standingsService: StandingsService,
    private val properties: SofaScoreProperties,
) {

    @GetMapping("/rounds")
    fun rounds(): List<RoundSummaryDto> = RoundSummaryDto.from(predictionService.rounds(properties.season))

    /**
     * A round with no predictions still has fixtures and scores worth reading, so it falls
     * back to the provider's own view of it rather than answering nothing at all.
     */
    @GetMapping("/rounds/{number}")
    fun round(@PathVariable number: Int): ResponseEntity<RoundDto> =
        predictionService.round(properties.season, number)
            ?.let { ResponseEntity.ok(RoundDto.from(it)) }
            ?: predictionService.fixtures(properties.season, number)
                .takeIf { it.isNotEmpty() }
                ?.let { ResponseEntity.ok(RoundDto.from(properties.season, number, it)) }
            ?: ResponseEntity.notFound().build()

    @PostMapping("/rounds/{number}/predict")
    fun predict(@PathVariable number: Int): RoundDto =
        RoundDto.from(predictionService.predict(properties.season, number))

    /**
     * The provider's odds are a proxy for what is actually on offer, so they can be corrected
     * here. The correction sticks: the next voorspel lays it back over the fetched book.
     */
    @PutMapping("/rounds/{number}/odds")
    fun editOdds(@PathVariable number: Int, @RequestBody edit: OddsEditDto): RoundDto = RoundDto.from(
        predictionService.editOdds(properties.season, number, edit.fixtureId, edit.market(), edit.odds)
    )

    @GetMapping("/standings")
    fun standings(): ResponseEntity<StandingsDto> =
        standingsService.latest(properties.season)
            ?.let { ResponseEntity.ok(StandingsDto.from(it)) }
            ?: ResponseEntity.notFound().build()

    @PostMapping("/standings/refresh")
    fun refreshStandings(): StandingsDto = StandingsDto.from(standingsService.refresh(properties.season))
}

data class ErrorDto(val message: String)

@RestControllerAdvice
class ApiExceptionHandler {

    /**
     * A refresh that fails leaves the stored round untouched rather than half-written, so the
     * client can retry once the provider or the standings catch up. A round that has already
     * kicked off is the one case that will never come good again.
     */
    @ExceptionHandler(IncompleteStandingsException::class, RoundAlreadyStartedException::class)
    fun cannotPredict(exception: IllegalStateException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorDto(exception.message.orEmpty()))

    /**
     * Named as SofaScore's, because the view shows this message as it stands and a raw provider
     * error there reads as our own. Nothing here can fix one: the call went out and came back
     * wrong, so the only useful thing to say is whose fault it is and that retrying is the move.
     */
    @ExceptionHandler(SofaScoreException::class)
    fun providerFailure(exception: SofaScoreException) = ResponseEntity
        .status(HttpStatus.BAD_GATEWAY)
        .body(ErrorDto("Internal SofaScore error: ${exception.message.orEmpty()}"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(exception: IllegalArgumentException) =
        ResponseEntity.badRequest().body(ErrorDto(exception.message.orEmpty()))
}
