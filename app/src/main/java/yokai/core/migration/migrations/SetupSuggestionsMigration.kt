package yokai.core.migration.migrations

import android.app.Application
import eu.kanade.tachiyomi.data.suggestions.SuggestionsWorker
import yokai.core.migration.Migration
import yokai.core.migration.MigrationContext

class SetupSuggestionsMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        SuggestionsWorker.setupTask(context)
        return true
    }
}
