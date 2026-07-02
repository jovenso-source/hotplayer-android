import { Injectable, OnModuleInit, OnModuleDestroy, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Pool, PoolClient, QueryResult } from 'pg';

/**
 * Service de connexion PostgreSQL.
 * Utilise un pool de connexions pour la performance.
 * Expose query() et withTransaction() pour les repositories.
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(DatabaseService.name);
  private pool!: Pool;

  constructor(private config: ConfigService) {}

  onModuleInit() {
    this.pool = new Pool({
      host:     this.config.get<string>('database.host'),
      port:     this.config.get<number>('database.port'),
      database: this.config.get<string>('database.name'),
      user:     this.config.get<string>('database.user'),
      password: this.config.get<string>('database.password'),
      max: 20,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
    });

    this.pool.on('error', (err) => this.logger.error('PG pool error', err.message));
    this.logger.log('Database pool initialized');
  }

  async onModuleDestroy() {
    await this.pool.end();
  }

  async query<T = unknown>(sql: string, params?: unknown[]): Promise<QueryResult<T>> {
    return this.pool.query<T>(sql, params);
  }

  /**
   * Exécute le callback dans une transaction PostgreSQL.
   * Rollback automatique en cas d'exception.
   */
  async withTransaction<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await fn(client);
      await client.query('COMMIT');
      return result;
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
  }
}
