package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY isPinned DESC, position ASC, timestamp DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Int): Note?

    @Query("SELECT * FROM notes WHERE noteId = :noteId")
    suspend fun getNoteByNoteId(noteId: String): Note?

    @Query("SELECT * FROM notes")
    suspend fun getAllNotesDirect(): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Int)

    @Query("DELETE FROM notes WHERE noteId = :noteId")
    suspend fun deleteNoteByNoteId(noteId: String)

    @Query("UPDATE notes SET category = :newCategory WHERE category = :oldCategory AND deletedAt IS NULL")
    suspend fun updateCategoryName(oldCategory: String, newCategory: String)
}
