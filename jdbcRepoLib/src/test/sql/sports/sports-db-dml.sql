-- Insert sports
INSERT INTO sports (name, type, location)
VALUES ('Football', 'TEAM', 'OUTDOOR'),
       ('Basketball', 'TEAM', 'INDOOR'),
       ('Tennis', 'TEAM', 'OUTDOOR');

-- Insert presidents
INSERT INTO presidents (name, birthdate)
VALUES ('Frederico Varandas', '1979-09-19'),
       ('Rui Costa', '1972-03-29'),
       ('André Villas-Boas', '1977-08-17'),
       ('Paulo Rosado', '1966-06-26');

-- Insert clubs
INSERT INTO clubs (name, year, president)
VALUES ('Sporting', 1902, 1),
       ('Benfica', 1899, 2),
       ('Porto', 1878, 3);

-- Insert teams
INSERT INTO teams (name, sport, club)
VALUES ('Sporting Football', 'Football', 1),
       ('Benfica Football', 'Football', 2),
       ('Porto Football', 'Football', 3),
       ('Sporting Basketball', 'Basketball', 1),
       ('Benfica Basketball', 'Basketball', 2),
       ('Porto Basketball', 'Basketball', 3),
       ('Sporting Tennis', 'Tennis', 1),
       ('Benfica Tennis', 'Tennis', 2),
       ('Porto Tennis', 'Tennis', 3);