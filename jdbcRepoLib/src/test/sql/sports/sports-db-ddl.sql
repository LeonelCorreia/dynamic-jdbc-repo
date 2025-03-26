-- Enum for Location
CREATE TYPE location AS ENUM ('INDOOR', 'OUTDOOR');

-- Enum for SportType
CREATE TYPE sport_type AS ENUM ('TEAM', 'INDIVIDUAL');

-- Sport table
CREATE TABLE sports
(
    name        VARCHAR(255) PRIMARY KEY,
    type        sport_type   NOT NULL,
    location    location     NOT NULL
);

-- Director table
CREATE TABLE presidents
(
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    birthdate   DATE         NOT NULL
);

-- Club table
CREATE TABLE clubs
(
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) UNIQUE NOT NULL,
    year        INT          NOT NULL CHECK (year > 0),
    president   INT          NOT NULL REFERENCES presidents (id) ON DELETE CASCADE
);

-- Team table
CREATE TABLE teams
(
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(255) UNIQUE,
    sport      VARCHAR(255) NOT NULL REFERENCES sports (name) ON DELETE CASCADE,
    club       INT NOT NULL REFERENCES clubs (id) ON DELETE CASCADE
);
