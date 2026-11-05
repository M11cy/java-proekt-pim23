UPDATE questions SET reviewed_at=(
  SELECT substr(max(r.saved_at),1,10)
  FROM question_revisions r
  LEFT JOIN question_revisions previous
    ON previous.question_id=r.question_id AND previous.revision_no=r.revision_no-1
  WHERE r.question_id=questions.id AND (
    previous.revision_no IS NULL OR r.topic_id<>previous.topic_id OR r.text<>previous.text
    OR r.type<>previous.type OR r.difficulty<>previous.difficulty OR r.importance<>previous.importance
    OR r.max_points_scaled<>previous.max_points_scaled OR r.guidance<>previous.guidance
  )
);

CREATE TABLE ranking_word_uses(
  document_key TEXT NOT NULL,
  question_id INTEGER NOT NULL REFERENCES questions(id),
  PRIMARY KEY(document_key,question_id)
);
CREATE INDEX idx_ranking_word_question ON ranking_word_uses(question_id);

CREATE TABLE ranking_exam_discrepancies(
  result_key TEXT NOT NULL,
  question_id INTEGER NOT NULL,
  revision INTEGER NOT NULL,
  discrepancy INTEGER NOT NULL CHECK(discrepancy BETWEEN 0 AND 4),
  PRIMARY KEY(result_key,question_id,revision),
  FOREIGN KEY(question_id,revision) REFERENCES question_revisions(question_id,revision_no)
);
CREATE INDEX idx_ranking_exam_question ON ranking_exam_discrepancies(question_id,revision);

CREATE TABLE question_rankings(
  question_id INTEGER PRIMARY KEY,
  revision INTEGER NOT NULL,
  profile_id INTEGER NOT NULL REFERENCES ranking_profiles(id),
  calculated_on TEXT NOT NULL,
  score REAL NOT NULL CHECK(score BETWEEN 0 AND 100),
  difficulty INTEGER NOT NULL CHECK(difficulty BETWEEN 1 AND 5),
  FOREIGN KEY(question_id,revision) REFERENCES question_revisions(question_id,revision_no)
);
