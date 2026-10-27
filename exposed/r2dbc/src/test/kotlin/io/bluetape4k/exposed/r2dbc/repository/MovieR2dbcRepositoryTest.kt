package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.exposed.r2dbc.domain.model.MovieRecord
import io.bluetape4k.exposed.r2dbc.domain.model.MovieSchema.withMovieAndActors
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MovieR2dbcRepositoryTest: AbstractExposedR2dbcTest() {

    companion object: KLoggingChannel() {
        private fun newMovieRecord(): MovieRecord = MovieRecord(
            id = 0L,
            name = faker.book().title(),
            producerName = faker.name().fullName(),
            releaseDate = faker.timeAndDate().birthday(10, 90).toString()
        )
    }

    private val repository: MovieR2dbcRepository = MovieR2dbcRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find movie by id`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val movieId = 1L
            val movie = repository.findById(movieId)

            log.debug { "movie: $movie" }
            movie.id shouldBeEqualTo movieId
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `search movies`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val params = mapOf("producerName" to "Johnny")

            val movies = repository.searchMovies(params).log("MOVIE").toList()
            movies shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create movie`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val currentCount = repository.count() shouldBeGreaterThan 0L

            val movie = newMovieRecord()
            val savedMovie = repository.save(movie)
            savedMovie shouldBeEqualTo movie.withId(id = savedMovie.id)

            val newCount = repository.count()
            newCount shouldBeEqualTo currentCount + 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete movie`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val newMovie = newMovieRecord()
            val savedMovie = repository.save(newMovie)

            val deletedCount = repository.deleteById(savedMovie.id)
            deletedCount shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `get all movies and actors`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val movieWithActors = repository.getAllMoviesWithActors()
                .log("Movie With Actors")
                .toList()

            movieWithActors.shouldNotBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `get movie by id with actors`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val movieId = 1L
            val movieWithActors = repository.getMovieWithActors(movieId)

            log.debug { "movieWithActors: $movieWithActors" }

            movieWithActors.shouldNotBeNull()
            movieWithActors.id shouldBeEqualTo movieId
            movieWithActors.actors shouldHaveSize 3
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `get movie and actor count`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val movieWithActors = repository.getMovieActorsCount().log("Movie").toList()
            movieWithActors.shouldNotBeEmpty() shouldHaveSize 4
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find movies with acting producers`(testDB: TestDB) = runSuspendIO {
        withMovieAndActors(testDB) {
            val results = repository.findMoviesWithActingProducers().log("Movie").toList()
            results shouldHaveSize 1
        }
    }
}
